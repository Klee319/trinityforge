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
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the High bug fix (Fix A) through the real {@link CombatListener}: a BARE bow (no
 * item meta) fired via {@link EntityShootBowEvent} is retained on the arrow, so at impact
 * ({@link EntityDamageByEntityEvent}) the attack stats are re-derived from the FIRING bow even after the
 * shooter has swapped to a different mainhand weapon. item-stats.yml gives BOW a much larger
 * attack-power than the swapped-in sword, so the impact damage cleanly reveals which weapon was used.
 */
@SuppressWarnings("removal") // deprecated-for-removal event ctors are the only test-constructable ones.
class CombatListenerProjectileIntegrationTest {

    // Wide separation so combat-level scaling / minor vanilla armor can't blur which weapon was sourced.
    private static final double BOW_ATTACK_POWER = 50.0;
    private static final double SWORD_ATTACK_POWER = 5.0;
    private static final double SEPARATION = 20.0; // between the two attack-powers after mitigation.

    private ServerMock server;
    private WorldMock world;
    private CombatListener listener;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        // CombatListener.expAllowedInWorld() reads TrinityForge.getInstance().dungeonWorldRegistry() when
        // stats/skill-exp.yml's dungeon-only-exp is true (its shipped default, loaded here via
        // CombatWiringSupport). createMockPlugin() has no plugin lifecycle, so the real singleton is never
        // published; stub it so that path resolves instead of NPE-ing (TrinityForgeSingletonTestSupport).
        // These tests only assert impact damage, not EXP gating, so an empty (non-dungeon) registry is fine.
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
        // Pre-write item-stats.yml so the loader keeps it (BOW/DIAMOND_SWORD get distinct attack-power);
        // every other domain gets its shipped default via the resource-copying fake plugin.
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  BOW:
                    fixed: { attack-power: %s }
                  DIAMOND_SWORD:
                    fixed: { attack-power: %s }
                """.formatted(BOW_ATTACK_POWER, SWORD_ATTACK_POWER));

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        Plugin plugin = MockBukkit.createMockPlugin();
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), cm.combatDamage(), perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(
                cm.combatDamage().defenseStatKeys(), aggregator);
        // EMPTY skills -> combat level 0 -> no level multiplier, so the raw attack-power drives the result.
        SymmetricCombatService svc = new SymmetricCombatService(
                cm.combatDamage(), cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
        BleedService bleed = new BleedService(plugin, svc, cm.combatDamage());
        return new CombatListener(plugin, svc, cm.itemStats(),
                cm.combatDamage(), SkillLevelSource.EMPTY, bleed, perks, aggregator,
                cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                new RoleBuffResolver(cm.roleBuffs()));
    }

    /** Same fixture as {@link #listener(File)} but with {@code min-component-damage: 0} so a
     * zero-attack-power shot (force=0) can be asserted at an exact {@code 0.0}, not the shipped
     * default floor of {@code 1}. */
    private CombatListener listenerWithNoDamageFloor(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  BOW:
                    fixed: { attack-power: %s }
                """.formatted(BOW_ATTACK_POWER));

        String damageYaml = """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 0.0
                """;
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, damageYaml);
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

    private double impactDamage(Player shooter, Arrow arrow, Zombie victim) {
        arrow.setShooter(shooter);
        DamageSource source = DamageSource.builder(DamageType.ARROW).withDirectEntity(arrow)
                .withCausingEntity(shooter).build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                arrow, victim, EntityDamageEvent.DamageCause.PROJECTILE, source, 6.0);
        listener.onEntityDamageByEntity(event);
        return event.getDamage();
    }

    @Test
    void firedBareBowStatsUsedAtImpactEvenAfterSwappingMainhand(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player shooter = server.addPlayer();
        Zombie victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        // Fire a BARE bow (no meta): the listener must retain it on the arrow (Fix A).
        ItemStack bow = new ItemStack(Material.BOW);
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        listener.onEntityShootBow(new EntityShootBowEvent(shooter, bow, arrow, 1.0f));

        // Shooter swaps to a weaker sword AFTER loosing the shot; impact must still use the bow's stats.
        shooter.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));

        double damage = impactDamage(shooter, arrow, victim);
        assertTrue(damage > SEPARATION,
                "impact must reflect the firing bow's attack-power (" + BOW_ATTACK_POWER + "), not the "
                        + "swapped-in sword's (" + SWORD_ATTACK_POWER + "); got " + damage);
    }

    @Test
    void unstoredProjectileFallsBackToMainhand(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player shooter = server.addPlayer();
        Zombie victim = world.spawn(world.getSpawnLocation(), Zombie.class);
        // A spawned arrow that was never fired carries no stored weapon, so the impact path falls back to
        // the shooter's current mainhand (the pre-Fix behaviour, preserved).
        shooter.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);

        double damage = impactDamage(shooter, arrow, victim);
        assertTrue(damage > 0.0 && damage < SEPARATION,
                "with no firing weapon retained the impact must fall back to the mainhand sword ("
                        + SWORD_ATTACK_POWER + "); got " + damage);
    }

    // --- 2026-07-25バグ修正 (#3): 弓の引き絞り量が attack-power 置換で消えていた ---

    /**
     * 満額チャージ(force=1.0)と1/4チャージ(force=0.25)を同一の弓/射手/被害者で撃ち比べる。
     * BOWのattack-powerは全ステの唯一のダメージ源(item-stats.yml上、他のstat/enchant/perkは無し)で、
     * かつcombat levelは両ショットとも0(SkillLevelSource.EMPTY)で共通なので、パイプラインの
     * defaultDamage乗率(coefficient×(1+perLevel×level))は両者で完全に同一 — force倍率だけが差になる
     * ため、比率がちょうど4倍になることが「二重計上でも消失でもなく、ちょうどforce分だけ効いている」
     * ことの直接証拠になる。
     */
    @Test
    void bowDrawForceScalesImpactDamageProportionally(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player shooter = server.addPlayer();

        ItemStack bow = new ItemStack(Material.BOW);

        Arrow fullDraw = world.spawn(world.getSpawnLocation(), Arrow.class);
        listener.onEntityShootBow(new EntityShootBowEvent(shooter, bow, fullDraw, 1.0f));
        Zombie fullDrawVictim = world.spawn(world.getSpawnLocation(), Zombie.class);
        double fullDrawDamage = impactDamage(shooter, fullDraw, fullDrawVictim);

        Arrow quarterDraw = world.spawn(world.getSpawnLocation(), Arrow.class);
        listener.onEntityShootBow(new EntityShootBowEvent(shooter, bow, quarterDraw, 0.25f));
        Zombie quarterDrawVictim = world.spawn(world.getSpawnLocation(), Zombie.class);
        double quarterDrawDamage = impactDamage(shooter, quarterDraw, quarterDrawVictim);

        assertTrue(fullDrawDamage > quarterDrawDamage,
                "a fully-drawn shot must deal more damage than a lightly-drawn one; full="
                        + fullDrawDamage + " quarter=" + quarterDrawDamage);
        assertEquals(4.0, fullDrawDamage / quarterDrawDamage, 0.01,
                "damage must scale linearly with draw force (1.0 vs 0.25 -> exactly 4x), "
                        + "not be flattened to a single flat value by the attack-power replacement");
    }

    /** force=0(理論上の下限)なら弓由来の攻撃力寄与は0まで落ちる(=引き絞りスケールが本当に効いている証拠)。 */
    @Test
    void zeroDrawForceYieldsNoWeaponAttackPowerContribution(@TempDir File dir) throws IOException {
        listener = listenerWithNoDamageFloor(dir);
        Player shooter = server.addPlayer();
        Zombie victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        ItemStack bow = new ItemStack(Material.BOW);
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        listener.onEntityShootBow(new EntityShootBowEvent(shooter, bow, arrow, 0.0f));

        double damage = impactDamage(shooter, arrow, victim);
        assertEquals(0.0, damage, 1e-6,
                "force=0 must scale the bow's attack-power contribution down to 0 (no perk/addon "
                        + "attack-power configured in this fixture)");
    }
}
