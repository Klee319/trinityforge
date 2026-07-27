package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the {@link AttackStats} compact constructor: non-finite inputs collapse to 0, positive
 * probability/rate overflow is capped, and authored negative values survive for cursed/gamble gear.
 */
class AttackStatsValidationTest {

    @Test
    void penetrationAboveOneIsClampedToOne() {
        AttackStats stats = new AttackStats(10, 0, 0, 0, 0, 1.5, 0, 0);
        assertEquals(1.0, stats.penetration());
    }

    @Test
    void penetrationClampPreventsDamageAmplification() {
        // Before the clamp, penetration=1.5 against 50% defense rate would flip
        // (1 - defenseRate * (1 - penetration)) into > 1, amplifying instead of mitigating.
        AttackStats attack = new AttackStats(10, 0, 0, 0, 0, 1.5, 0, 0);
        DefenseStats defense = new DefenseStats(0.5, 0, 0, 0, 0);
        double dmg = ComponentDamageCalculator.compute(attack, defense, false, 1.0);
        assertTrue(dmg <= 10.0, "clamped penetration must never exceed the unmitigated base damage");
    }

    @Test
    void negativePenetrationIsPreserved() {
        AttackStats stats = new AttackStats(10, 0, 0, 0, 0, -0.3, 0, 0);
        assertEquals(-0.3, stats.penetration());
    }

    @Test
    void negativeCritChanceIsPreserved() {
        AttackStats stats = new AttackStats(10, 0, 0, -0.4, 0, 0, 0, 0);
        assertEquals(-0.4, stats.critChance());
    }

    @Test
    void critChanceAboveOneIsClampedToOne() {
        AttackStats stats = new AttackStats(10, 0, 0, 1.4, 0, 0, 0, 0);
        assertEquals(1.0, stats.critChance());
    }

    @Test
    void nanFieldsCollapseToZero() {
        AttackStats stats = new AttackStats(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        assertEquals(0.0, stats.defaultDamage());
        assertEquals(0.0, stats.flatBonusDamage());
        assertEquals(0.0, stats.percentBonusDamage());
        assertEquals(0.0, stats.critChance());
        assertEquals(0.0, stats.critDamage());
        assertEquals(0.0, stats.penetration());
        assertEquals(1.0, stats.damageModifier());
        assertEquals(0.0, stats.fixedDamage());
    }

    @Test
    void infiniteFieldsCollapseToZero() {
        AttackStats stats = new AttackStats(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                0, 0, 0, 0, 0, 0);
        assertEquals(0.0, stats.defaultDamage());
        assertEquals(0.0, stats.flatBonusDamage());
    }

    @Test
    void withDefaultDamagePreservesValidatedFields() {
        AttackStats stats = new AttackStats(1, 0, 0, 1.5, 0, 1.5, 0, 0).withDefaultDamage(20);
        assertEquals(20.0, stats.defaultDamage());
        assertEquals(1.0, stats.critChance());
        assertEquals(1.0, stats.penetration());
    }
}
