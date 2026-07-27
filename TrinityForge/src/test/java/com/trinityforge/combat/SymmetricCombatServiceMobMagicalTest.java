package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.pdc.MobData;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mob→player magical reception ports (B1: EliteMobs ability damage routed through TF):
 * {@link SymmetricCombatService#magicalFinalDamageFromMob} must scale the ability base by the MOB's
 * stamped level (the mob level is the difficulty knob for both components, unlike the player-side
 * C2 combat-level bypass), honour the attack-power replacement rule, and
 * {@link SymmetricCombatService#magicalFinalDamageFlat} must pass an already-finalized base through
 * with no level/coefficient re-scaling — coinciding with a level-0 mob's scaled hit at coefficient
 * 1.0 (behaviour-safe delegation from the EliteMobs fork).
 */
class SymmetricCombatServiceMobMagicalTest {

    private static final double ABILITY_BASE = 100.0;
    private static final double EPS = 1e-9;

    private static final SkillLevelSource NO_SKILLS = id -> Map.of();

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private SymmetricCombatService service(File dir) throws IOException {
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 1.0
                magical:
                  base-coefficient: 1.0
                  min-component-damage: 1.0
                  scale-with-combat-level: false
                level-scaling:
                  per-level: 0.05
                """);
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        return new SymmetricCombatService(damage, cm.combatLevel(), cm.mobTypes(), NO_SKILLS, defense);
    }

    /** Spawns a mob stamped with {@code level} (zero defense profile so only the level matters). */
    private Zombie stampedMob(int level) {
        Zombie mob = world.spawn(world.getSpawnLocation(), Zombie.class);
        MobData.stamp(mob, level, DefenseStats.NONE, DefenseStats.NONE);
        return mob;
    }

    @Test
    void mobMagicalScalesWithStampedMobLevel(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir);
        Player victim = server.addPlayer();

        double fromHighLevelMob = svc.magicalFinalDamageFromMob(
                stampedMob(40), victim, ABILITY_BASE, AttackStats.plain(0));
        double fromZeroLevelMob = svc.magicalFinalDamageFromMob(
                stampedMob(0), victim, ABILITY_BASE, AttackStats.plain(0));

        assertTrue(fromHighLevelMob > fromZeroLevelMob,
                "a level-40 mob's ability (" + fromHighLevelMob + ") must exceed a level-0 mob's ("
                        + fromZeroLevelMob + ") — mob level is the difficulty knob");
        // per-level 0.05, level 40 -> ×3 exactly (no defense on a fresh player).
        assertEquals(fromZeroLevelMob * 3.0, fromHighLevelMob, EPS);
    }

    @Test
    void stampedAttackPowerReplacesScaledAbilityBase(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir);
        Player victim = server.addPlayer();
        Zombie mob = stampedMob(40);

        AttackStats stamped = AttackStats.plain(25.0);
        double withAttackPower = svc.magicalFinalDamageFromMob(mob, victim, ABILITY_BASE, stamped);

        // attack-power replaces the level-scaled base outright: 25, not 100 * 3.
        assertEquals(25.0, withAttackPower, EPS);
    }

    @Test
    void magicalFlatCoincidesWithLevelZeroMobAndBypassesLevel(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir);
        Player victim = server.addPlayer();

        double flat = svc.magicalFinalDamageFlat(victim, ABILITY_BASE, AttackStats.plain(0));
        double scaledZero = svc.magicalFinalDamageFromMob(
                stampedMob(0), victim, ABILITY_BASE, AttackStats.plain(0));
        double scaledHigh = svc.magicalFinalDamageFromMob(
                stampedMob(40), victim, ABILITY_BASE, AttackStats.plain(0));

        assertEquals(scaledZero, flat, EPS,
                "at coefficient 1.0 the flat path must equal a level-0 mob's scaled hit (behaviour-safe)");
        assertTrue(flat < scaledHigh,
                "the flat path must NOT pick up the mob's level scaling (" + flat + " < " + scaledHigh + ")");
    }
}
