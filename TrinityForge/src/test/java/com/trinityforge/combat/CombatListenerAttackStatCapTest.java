package com.trinityforge.combat;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.StatCapsConfig;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-07-26 stat-caps カバレッジ拡大: ATTACK チャネル(attack-power / fixed-damage 等)が
 * {@link CombatListener} の実イベント経路で {@code combat/stat-caps.yml} の上限に実際に服すること、
 * かつ上限未設定(statCaps=null、既定)なら計算結果が1ビットも変わらないことの両方を固定する。
 *
 * <p>被害者は完全無装備(防御0)、{@code physical.min-component-damage: 0}、{@code melee-charge.enabled:
 * false}、crit-chance未設定(=0)なので、最終ダメージは
 * {@code attack-power(倍率適用後、上限適用後) + fixed-damage(上限適用後)} の厳密な決定値になる
 * ({@link CombatListenerMeleeChargeTest} と同じ「防御ゼロで素通り」パターンを踏襲)。
 */
class CombatListenerAttackStatCapTest {

    private ServerMock server;

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

    private static final String DAMAGE_YAML = """
            physical:
              base-coefficient: 1.0
              min-component-damage: 0.0
            melee-charge:
              enabled: false
            """;

    private void writeItemStats(File dir, double attackPower, double fixedDamage) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-power: %s, fixed-damage: %s, damage-modifier: 1.0 }
                """.formatted(attackPower, fixedDamage));
    }

    /** {@code statCapsYaml} が null なら statCaps 未設定(4引数コンストラクタ相当)で組み立てる。 */
    private CombatListener listener(File dir, double attackPower, double fixedDamage, String statCapsYaml)
            throws IOException {
        writeItemStats(dir, attackPower, fixedDamage);
        if (statCapsYaml != null) {
            File statCaps = new File(dir, StatCapsConfig.PATH);
            Files.createDirectories(statCaps.getParentFile().toPath());
            Files.writeString(statCaps.toPath(), statCapsYaml);
        }
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, DAMAGE_YAML);
        Plugin plugin = MockBukkit.createMockPlugin();
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = statCapsYaml != null
                ? new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()),
                        null, null, null, cm.statCaps())
                : new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        SymmetricCombatService svc = new SymmetricCombatService(
                damage, cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
        BleedService bleed = new BleedService(plugin, svc, damage);
        return new CombatListener(plugin, svc, cm.itemStats(),
                damage, SkillLevelSource.EMPTY, bleed, perks, aggregator,
                cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                new RoleBuffResolver(cm.roleBuffs()));
    }

    private static double strike(CombatListener listener, Player attacker, Player victim) {
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0);
        listener.onEntityDamageByEntity(event);
        return event.getDamage();
    }

    @Test
    void attackPowerIsClampedByStatCaps(@TempDir File dir) throws IOException {
        CombatListener listener = listener(dir, 1000.0, 0.0, """
                stat-caps:
                  attack-power: 100.0
                """);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        Player victim = server.addPlayer();

        double damage = strike(listener, attacker, victim);

        assertEquals(100.0, damage, 1e-6,
                "attack-power(1000) must be clamped to the configured cap(100) before it reaches baseDamage");
    }

    @Test
    void fixedDamageIsClampedViaAttackerStatsLoop(@TempDir File dir) throws IOException {
        CombatListener listener = listener(dir, 1.0, 500.0, """
                stat-caps:
                  fixed-damage: 50.0
                """);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        Player victim = server.addPlayer();

        double damage = strike(listener, attacker, victim);

        // attack-power(1.0, 未設定=無上限) + fixed-damage(500 -> 50 にクランプ) = 51.0。
        assertEquals(51.0, damage, 1e-6,
                "fixed-damage(500) must be clamped to the configured cap(50) via the attackerStats loop, "
                        + "while the uncapped attack-power(1.0) passes through unchanged");
    }

    @Test
    void nullStatCapsLeavesAttackDamageCompletelyUnchanged(@TempDir File dir) throws IOException {
        CombatListener listener = listener(dir, 1000.0, 9.0, null);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        Player victim = server.addPlayer();

        double damage = strike(listener, attacker, victim);

        assertEquals(1009.0, damage, 1e-6,
                "with no stat-caps.yml wired in at all (statCaps=null), attack-power(1000)+fixed-damage(9) "
                        + "must reach baseDamage completely unclamped, matching pre-existing behaviour");
    }
}
