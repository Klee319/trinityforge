package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-function checks for the weapon-PDC derived stat map -&gt; {@link AttackStats} bridge
 * (COMBAT_SYSTEM_SPEC 3.1), driven by the configured {@code attack-stat-keys.*} mapping.
 */
class AttackStatBridgeTest {

    private static final AttackStatKeys DEFAULT_KEYS = new AttackStatKeys(
            "flat-bonus-damage", "percent-bonus-damage", "crit-chance", "crit-damage",
            "penetration", "damage-modifier", "fixed-damage");

    @Test
    void mapsEachConfiguredKeyToItsField() {
        Map<String, Double> derived = Map.of(
                "flat-bonus-damage", 3.0,
                "percent-bonus-damage", 0.1,
                "crit-chance", 0.25,
                "crit-damage", 0.5,
                "penetration", 0.2,
                "damage-modifier", 0.05,
                "fixed-damage", 4.0);

        AttackStats stats = AttackStatBridge.bridge(derived, DEFAULT_KEYS);

        assertEquals(0.0, stats.defaultDamage(), "defaultDamage is never PDC-derived");
        assertEquals(3.0, stats.flatBonusDamage());
        assertEquals(0.1, stats.percentBonusDamage());
        assertEquals(0.25, stats.critChance());
        assertEquals(0.5, stats.critDamage());
        assertEquals(0.2, stats.penetration());
        assertEquals(0.05, stats.damageModifier());
        assertEquals(4.0, stats.fixedDamage());
    }

    @Test
    void missingKeyContributesZero() {
        AttackStats stats = AttackStatBridge.bridge(Map.of("crit-chance", 0.3), DEFAULT_KEYS);
        assertEquals(0.3, stats.critChance());
        assertEquals(0.0, stats.flatBonusDamage());
        assertEquals(0.0, stats.fixedDamage());
        assertEquals(1.0, stats.damageModifier(), "unset damage modifier must remain neutral");
    }

    @Test
    void emptyDerivedMapYieldsPlainAttack() {
        AttackStats stats = AttackStatBridge.bridge(Map.of(), DEFAULT_KEYS);
        assertEquals(AttackStats.plain(0), stats);
    }

    @Test
    void derivedKeysAreCanonicalizedAgainstConfiguredKebabKeys() {
        // stats/roll.yml authors kebab-case; a config author could still spell attack-stat-keys
        // in snake_case. StatKeys#canonical must make both sides agree (COMBAT gap I1).
        AttackStatKeys snakeKeys = new AttackStatKeys(
                "flat_bonus_damage", "percent_bonus_damage", "crit_chance", "crit_damage",
                "penetration", "damage_modifier", "fixed_damage");
        Map<String, Double> derived = Map.of("crit-chance", 0.4, "flat-bonus-damage", 2.0);

        AttackStats stats = AttackStatBridge.bridge(derived, snakeKeys);

        assertEquals(0.4, stats.critChance());
        assertEquals(2.0, stats.flatBonusDamage());
    }

    @Test
    void configuredKeysAreCanonicalizedAgainstSnakeCaseDerivedKeys() {
        Map<String, Double> derived = Map.of("crit_chance", 0.6);
        AttackStats stats = AttackStatBridge.bridge(derived, DEFAULT_KEYS);
        assertEquals(0.6, stats.critChance());
    }

    @Test
    void explicitZeroAndNegativeDamageModifiersArePreserved() {
        assertEquals(0.0,
                AttackStatBridge.bridge(Map.of("damage-modifier", 0.0), DEFAULT_KEYS).damageModifier());
        assertEquals(-0.75,
                AttackStatBridge.bridge(Map.of("damage-modifier", -0.75), DEFAULT_KEYS).damageModifier());
    }
}
