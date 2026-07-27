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
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CMB-02 (課題3, 2026-07-25) エンドツーエンド検証: {@link EliteCombatDelegation} がアクティブな間、
 * {@link CombatListener#onEntityDamageByEntity} は防御・回避・会心・貫通・combat-Lvスケールの再計算を
 * 一切行わず、フォークが既に確定したダメージ({@code event.getDamage()})をそのまま採用することを保証する
 * (=防御・会心・貫通が「1回だけ」適用される)。
 */
@SuppressWarnings("removal")
class CombatListenerEliteDelegationSkipTest {

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
        // 後始末: テスト失敗でclear漏れが起きても後続テストへ波及させない。
        while (EliteCombatDelegation.isActive()) {
            EliteCombatDelegation.clear();
        }
    }

    private CombatListener listener(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        // 巨大なflat-defenseを設定: マーカーOFF時はこれが効いて大きく減衰するはず。
        // マーカーON時はこの防御が一切適用されない(=フォーク供給値がそのまま通る)ことを確認する対照実験。
        Files.writeString(itemStats.toPath(), """
                items:
                  GOLDEN_SWORD:
                    fixed: { attack-power: 1000.0, damage-modifier: 1.0 }
                  DIAMOND_CHESTPLATE:
                    fixed: { flat-defense: 900.0 }
                """);

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: -100000.0
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

    private EntityDamageByEntityEvent hit(Player attacker, Player victim, double forkSuppliedDamage) {
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        return new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, forkSuppliedDamage);
    }

    @Test
    void withoutMarkerVictimDefenseIsAppliedAndDamageShrinks(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));
        Player victim = server.addPlayer();
        victim.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));

        double forkSupplied = 50.0;
        EntityDamageByEntityEvent event = hit(attacker, victim, forkSupplied);

        listener.onEntityDamageByEntity(event);

        // 未マーク時はTFが独自に attack-power=1000 の武器で再計算するため、フォーク供給値(50)とは
        // 全く異なる値になる(=通常の一撃としてTFのパイプラインを完全に通っている証拠)。
        assertNotEquals(forkSupplied, event.getDamage(), 1e-6,
                "without the marker, TrinityForge must run its own full pipeline (not adopt forkSupplied as-is)");
    }

    @Test
    void withMarkerActiveForkSuppliedDamageIsAdoptedAsIs(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));
        Player victim = server.addPlayer();
        victim.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));

        double forkSupplied = 50.0;
        EntityDamageByEntityEvent event = hit(attacker, victim, forkSupplied);

        EliteCombatDelegation.mark();
        try {
            listener.onEntityDamageByEntity(event);
        } finally {
            EliteCombatDelegation.clear();
        }

        // マーカーON時は防御(flat-defense 900)もcombat-Lvスケールも一切かからず、フォーク供給値が
        // そのまま最終ダメージになる(= 防御/会心/貫通/combat-Lvスケールの二重適用が起きていない証拠)。
        assertEquals(forkSupplied, event.getDamage(), 1e-6,
                "with the marker active, CombatListener must adopt the fork-priced damage verbatim"
                        + " instead of re-deriving defense/crit/penetration/combat-level a second time");
    }
}
