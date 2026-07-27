package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end behaviour of {@code magical.scale-with-combat-level} through the real
 * {@link SymmetricCombatService#magicalFinalDamage} path against a live (MockBukkit) victim. Two
 * services share every dependency and differ ONLY in the flag, so the difference in output isolates the
 * knob (C2: 魔法はcombatレベルbypass vs. サーバ方針で伸ばす). Config comes from the shipped defaults
 * (combat-level.yml min-level 0, LIGHT+HEAVY 100 -> level 80; level-scaling per-level 0.05).
 */
class SymmetricCombatServiceMagicalIntegrationTest {

    private static final double SPELL_BASE = 100.0;
    private static final double EPS = 1e-9;
    private static final UUID HIGH_LEVEL_PLAYER = UUID.randomUUID();
    private static final UUID ZERO_LEVEL_PLAYER = UUID.randomUUID();

    /** LIGHT+HEAVY 100 each -> combat level 80 for the "high" player; every other player trains nothing. */
    private static final SkillLevelSource SKILLS = id -> id.equals(HIGH_LEVEL_PLAYER)
            ? Map.of("LIGHT_WEAPONS", 100, "HEAVY_WEAPONS", 100)
            : Map.of();

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

    private SymmetricCombatService service(File dir, boolean scaleWithLevel) throws IOException {
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 1.0
                magical:
                  base-coefficient: 1.0
                  min-component-damage: 1.0
                  scale-with-combat-level: %s
                level-scaling:
                  per-level: 0.05
                """.formatted(scaleWithLevel));
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        return new SymmetricCombatService(damage, cm.combatLevel(), cm.mobTypes(), SKILLS, defense);
    }

    @Test
    void scaleTrue_magicalGrowsWithCombatLevel(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir, true);
        Zombie victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        double high = svc.magicalFinalDamage(HIGH_LEVEL_PLAYER, victim, SPELL_BASE, AttackStats.plain(0));
        double zero = svc.magicalFinalDamage(ZERO_LEVEL_PLAYER, victim, SPELL_BASE, AttackStats.plain(0));

        assertTrue(high > zero,
                "with scale-with-combat-level=true a level-80 attacker's magical hit (" + high
                        + ") must exceed a level-0 attacker's (" + zero + ")");
        assertTrue(zero > 0.0, "level-0 magical damage should still be positive");
    }

    @Test
    void scaleFalse_magicalBypassesCombatLevel(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir, false);
        Zombie victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        double high = svc.magicalFinalDamage(HIGH_LEVEL_PLAYER, victim, SPELL_BASE, AttackStats.plain(0));
        double zero = svc.magicalFinalDamage(ZERO_LEVEL_PLAYER, victim, SPELL_BASE, AttackStats.plain(0));

        assertEquals(zero, high, EPS,
                "with scale-with-combat-level=false the combat level must not change magical damage "
                        + "(multiplier collapses to 1)");
    }

    @Test
    void scaleTrueAndFalse_coincideAtLevelZero(@TempDir File dir) throws IOException {
        SymmetricCombatService scaled = service(dir, true);
        // A second temp dir keeps the two damage.yml files from clobbering each other.
        File other = new File(dir, "unscaled");
        SymmetricCombatService unscaled = service(other, false);
        Zombie victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        double scaledZero = scaled.magicalFinalDamage(ZERO_LEVEL_PLAYER, victim, SPELL_BASE, AttackStats.plain(0));
        double unscaledZero = unscaled.magicalFinalDamage(ZERO_LEVEL_PLAYER, victim, SPELL_BASE, AttackStats.plain(0));

        assertEquals(unscaledZero, scaledZero, EPS,
                "at combat level 0 the (1 + perLevel*level) multiplier is 1 for both settings");
    }
}
