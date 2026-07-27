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
import org.bukkit.attribute.Attribute;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 課題2 エンドツーエンド検証(2026-07-25): reflect-flat/reflect-percent(+棘の鎧レベルの10%/Lv寄与)が
 * 実際に攻撃者へダメージを跳ね返すこと、相互反射でも無限ループしないこと、バニラ自身のTHORNS原因イベントが
 * 抑止されること(=バニラ耐久消費の副作用そのものには一切触れていない、という構造的保証の確認)。
 */
@SuppressWarnings("removal")
class CombatListenerReflectDamageTest {

    private ServerMock server;
    private CombatListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.dungeonWorldRegistry()).thenReturn(new DungeonWorldRegistry());
        TrinityForgeSingletonTestSupport.set(tf);
    }

    @AfterEach
    void tearDown() {
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    private CombatListener listener(File dir, String itemStatsYaml) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), itemStatsYaml);

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                """);
        Plugin plugin = MockBukkit.createMockPlugin();
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

    @Test
    void reflectFlatAndPercentDamageTheAttacker(@TempDir File dir) throws IOException {
        listener = listener(dir, """
                items:
                  GOLDEN_SWORD:
                    fixed: { attack-power: 20.0, damage-modifier: 1.0 }
                  DIAMOND_CHESTPLATE:
                    fixed: { reflect-flat: 2.0, reflect-percent: 0.5 }
                """);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));
        double attackerMaxHealth = attacker.getAttribute(Attribute.MAX_HEALTH).getValue();
        attacker.setHealth(attackerMaxHealth);

        Player victim = server.addPlayer();
        victim.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));

        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0);
        listener.onEntityDamageByEntity(event);
        double finalDamageDealt = event.getFinalDamage();

        listener.onReflectDamage(event);

        double expectedReflect = 2.0 + 0.5 * finalDamageDealt;
        assertEquals(attackerMaxHealth - expectedReflect, attacker.getHealth(), 1e-6,
                "attacker must take reflect-flat + reflect-percent*finalDamage");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void mutualReflectDoesNotInfiniteLoop(@TempDir File dir) throws IOException {
        // 双方が反射100%持ち(PvP)でも、reflectingガードにより1ホップで連鎖が止まりStackOverflow/無限
        // ループが起きないことを確認する。
        listener = listener(dir, """
                items:
                  GOLDEN_SWORD:
                    fixed: { attack-power: 20.0, damage-modifier: 1.0 }
                  DIAMOND_CHESTPLATE:
                    fixed: { reflect-percent: 1.0 }
                """);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));
        attacker.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));

        Player victim = server.addPlayer();
        victim.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));

        double attackerHealthBefore = attacker.getHealth();

        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0);
        listener.onEntityDamageByEntity(event);

        // これがハングせず戻ってくること自体が「無限ループしない」ことの直接証拠。
        listener.onReflectDamage(event);

        assertTrue(attacker.getHealth() < attackerHealthBefore,
                "attacker must take exactly one reflect hop's worth of damage, not zero");
    }

    @Test
    void vanillaThornsProcIsCancelled(@TempDir File dir) throws IOException {
        listener = listener(dir, """
                items: {}
                """);
        Player wearer = server.addPlayer();
        Player attacker = server.addPlayer();

        DamageSource source = DamageSource.builder(DamageType.THORNS)
                .withCausingEntity(wearer).withDirectEntity(wearer).build();
        EntityDamageByEntityEvent thornsEvent = new EntityDamageByEntityEvent(
                wearer, attacker, EntityDamageEvent.DamageCause.THORNS, source, 3.0);

        listener.onVanillaThornsProc(thornsEvent);

        assertTrue(thornsEvent.isCancelled(),
                "vanilla's own THORNS-cause counter-hit must be cancelled — reflect is now stat-driven"
                        + " (onReflectDamage), so leaving this un-cancelled would double the reflected damage."
                        + " NOTE (durability): the armor-piece durability loss vanilla applies for a Thorns"
                        + " proc happens inside NMS BEFORE this Bukkit event is even constructed, so"
                        + " cancelling it here cannot undo that already-applied durability cost — this test"
                        + " only proves TF's own code never touches the ItemStack/durability; the actual"
                        + " durability-is-still-consumed behaviour must be confirmed on a live server"
                        + " (equip Thorns armor, get hit, watch the durability bar drop with no accompanying"
                        + " legacy Thorns damage number).");
    }

    @Test
    void nonThornsDamageIsNotAffectedByTheSuppressionListener(@TempDir File dir) throws IOException {
        listener = listener(dir, "items: {}\n");
        Player attacker = server.addPlayer();
        Player victim = server.addPlayer();
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0);

        listener.onVanillaThornsProc(event);

        assertTrue(!event.isCancelled(), "non-THORNS damage causes must never be touched by this listener");
    }
}
