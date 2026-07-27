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
 * Locks in the FLAT physical entry point ({@link SymmetricCombatService#physicalFinalDamageFlat}) used by
 * bleed DoT (#5) and addon-delegated damage (#6): an already-finalized base must NOT be re-scaled by the
 * attacker's combat level nor by the {@code physical.base-coefficient}, while only the victim's defense
 * (and dodge) apply. The regular {@link SymmetricCombatService#physicalFinalDamage} DOES scale both, so
 * the two paths together isolate exactly the re-scaling that the flat path removes.
 */
class SymmetricCombatServicePhysicalFlatTest {

    private static final double BASE = 100.0;
    private static final double EPS = 1e-9;
    private static final UUID HIGH_LEVEL_PLAYER = UUID.randomUUID();
    private static final UUID ZERO_LEVEL_PLAYER = UUID.randomUUID();

    /** LIGHT+HEAVY 100 each -> combat level 80 for the "high" attacker; everyone else trains nothing. */
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

    private SymmetricCombatService service(File dir, double physicalCoefficient) throws IOException {
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: %s
                  min-component-damage: 1.0
                magical:
                  base-coefficient: 1.0
                  min-component-damage: 1.0
                  scale-with-combat-level: false
                level-scaling:
                  per-level: 0.05
                """.formatted(physicalCoefficient));
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        return new SymmetricCombatService(damage, cm.combatLevel(), cm.mobTypes(), SKILLS, defense);
    }

    @Test
    void flatBypassesCombatLevel_andCoincidesWithLevelZeroScaled(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir, 1.0);
        Zombie victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        double scaledHigh = svc.physicalFinalDamage(HIGH_LEVEL_PLAYER, victim, BASE, AttackStats.plain(0));
        double scaledZero = svc.physicalFinalDamage(ZERO_LEVEL_PLAYER, victim, BASE, AttackStats.plain(0));
        double flat = svc.physicalFinalDamageFlat(victim, BASE, AttackStats.plain(0));

        assertTrue(scaledHigh > scaledZero,
                "sanity: the regular physical path must grow with combat level (" + scaledHigh + " > " + scaledZero + ")");
        assertEquals(scaledZero, flat, EPS,
                "at coefficient 1.0 the flat path must equal a level-0 scaled hit (behaviour-safe rewire)");
        assertTrue(flat < scaledHigh,
                "the flat path must NOT pick up the level-80 attacker's scaling (" + flat + " < " + scaledHigh + ")");
    }

    @Test
    void flatBypassesBaseCoefficient(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir, 2.0);
        Zombie victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        // A level-0 attacker isolates the coefficient (no level multiplier): scaled = base*2, flat = base*1,
        // both minus the same victim defense, so the flat result must be strictly smaller.
        double scaledZero = svc.physicalFinalDamage(ZERO_LEVEL_PLAYER, victim, BASE, AttackStats.plain(0));
        double flat = svc.physicalFinalDamageFlat(victim, BASE, AttackStats.plain(0));

        assertTrue(flat < scaledZero,
                "the physical.base-coefficient (2.0) must scale the regular path but NOT the flat path ("
                        + flat + " < " + scaledZero + ")");
        assertTrue(flat > 0.0, "flat damage should remain positive");
    }
}
