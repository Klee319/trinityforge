package com.trinityforge.combat;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.dungeon.DungeonWorldRegistry;
import com.trinityforge.listeners.CombatListener;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 課題1 エンドツーエンド検証(2026-07-25): 防護IVフルセットが実際の {@link CombatListener} 経路で
 * 期待どおり約64%軽減されること(MAGIC modifier のゼロ化 + {@link DefenseEnchantmentBridge} 再導出の
 * 両方が正しく配線されていることの回帰ロック)。修正前は MAGIC modifier が未ゼロ化のまま
 * {@code setDamage(BASE, total)} が他modifierを再計算しないPaperの仕様により、バニラ由来の絶対値が
 * TFの最終ダメージへそのまま減算され、64%軽減が約1.8%まで壊れていた。
 */
@SuppressWarnings("removal") // deprecated-for-removal event ctors are the only test-constructable ones.
class CombatListenerProtectionMitigationTest {

    private ServerMock server;
    private CombatListener listener;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // total > 0 in every test here, so CombatListener.expAllowedInWorld() is reached, which reads
        // TrinityForge.getInstance().dungeonWorldRegistry() (dungeon-only-exp defaults true). Stub the
        // singleton like CombatListenerMeleeChargeTest does.
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.dungeonWorldRegistry()).thenReturn(new DungeonWorldRegistry());
        TrinityForgeSingletonTestSupport.set(tf);
    }

    @AfterEach
    void tearDown() {
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    private CombatListener listener(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        // 攻撃力を大きな固定値に置換(tfBaseReplaces=true)し、攻撃側の乱数要素(会心)を排除して
        // 軽減率の観測を決定的にする(crit-chance未設定=0)。
        Files.writeString(itemStats.toPath(), """
                items:
                  GOLDEN_SWORD:
                    fixed: { attack-power: 1000.0, damage-modifier: 1.0 }
                """);

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        // vanilla-armorのdefense-rate/armor-strengthを0にして、防具attribute由来の防御率(ARMORの
        // 再導出)がProtection由来の被ダメ軽減%と混ざらないよう分離する。defense.max-mitigation-rateは
        // 64%/80%より大きく保ち、キャップに引っかからないようにする。
        // enchant-protection-scale を1.0に固定: このテストはバニラ式そのもの(64%)の配線を検証する
        // 回帰ロックであり、バランス既定値(0.5)の影響を受けたくないため明示的にバニラ準拠へ上書きする
        // (2026-07-25 defense.enchant-protection-scale 追加)。
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                vanilla-armor:
                  defense-rate-per-point: 0.0
                  defense-rate-max: 0.0
                  armor-strength-per-point: 0.0
                defense:
                  max-mitigation-rate: 0.95
                  enchant-protection-scale: 1.0
                melee-charge:
                  enabled: false
                """);
        plugin = MockBukkit.createMockPlugin();
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        SymmetricCombatService svc = new SymmetricCombatService(
                damage, cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
        BleedService bleed = new BleedService(plugin, svc, damage);
        return new CombatListener(plugin, svc, cm.itemStats(),
                damage, SkillLevelSource.EMPTY, bleed, perks, aggregator,
                cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                new RoleBuffResolver(cm.roleBuffs()));
    }

    private static void setArmorPiece(Player player, int slot, Material material, Enchantment enchant, int level) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (enchant != null) {
            meta.addEnchant(enchant, level, true);
        }
        item.setItemMeta(meta);
        ItemStack[] armor = player.getInventory().getArmorContents();
        armor[slot] = item;
        player.getInventory().setArmorContents(armor);
    }

    private double strike(Player attacker, Player victim) {
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0);
        listener.onEntityDamageByEntity(event);
        return event.getDamage();
    }

    @Test
    void protectionIVFullSetReducesDamageByRoughly64Percent(@TempDir File dir) throws IOException {
        listener = listener(dir);

        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));

        Player unprotectedVictim = server.addPlayer();
        double unmitigated = strike(attacker, unprotectedVictim);

        Player protectedVictim = server.addPlayer();
        setArmorPiece(protectedVictim, 0, Material.DIAMOND_BOOTS, Enchantment.PROTECTION, 4);
        setArmorPiece(protectedVictim, 1, Material.DIAMOND_LEGGINGS, Enchantment.PROTECTION, 4);
        setArmorPiece(protectedVictim, 2, Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 4);
        setArmorPiece(protectedVictim, 3, Material.DIAMOND_HELMET, Enchantment.PROTECTION, 4);
        double mitigated = strike(attacker, protectedVictim);

        assertEquals(unmitigated * 0.36, mitigated, unmitigated * 0.01,
                "Protection IV full set (4 pieces x Lv4 = 16 EPF = 64% reduction) must leave ~36% of the"
                        + " unmitigated damage (unmitigated=" + unmitigated + ", mitigated=" + mitigated + ")");
    }

    @Test
    void noProtectionLeavesDamageUnchangedByMagicModifierZeroing(@TempDir File dir) throws IOException {
        // 回帰ガード: MAGIC modifierをゼロ化しても、防護エンチャント無しの相手には副作用が無いこと
        // (再導出が常に0を足すだけで、ダメージが変に減ったり増えたりしない)。
        listener = listener(dir);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));
        Player victim = server.addPlayer();

        double first = strike(attacker, victim);
        double second = strike(attacker, server.addPlayer());

        assertEquals(first, second, 1e-6, "unenchanted victims must take identical (unmitigated) damage");
    }
}
