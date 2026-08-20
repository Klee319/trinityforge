package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-function checks for the armor-PDC derived stat map -&gt; {@link DefenseStats} bridge
 * (SKILL_TREE_SPEC 6, LD-13), driven by the configured {@code defense-stat-keys.*} mapping.
 */
class DefenseStatBridgeTest {

    private static final double EPS = 1e-9;

    private static final DefenseStatKeys DEFAULT_KEYS = new DefenseStatKeys(
            "phys-resistance",
            "magic-resistance",
            "phys-flat-defense",
            "magic-flat-defense",
            "defense-rate",
            "damage-reduction",
            "dodge-chance",
            "armor-strength");

    private static final Map<String, Double> DERIVED = Map.of(
            "phys-resistance", 0.10,
            "magic-resistance", 0.20,
            "flat-defense", 3.0,
            "defense-rate", 0.12,
            "damage-reduction", 0.05,
            "dodge-chance", 0.08,
            "armor-strength", 0.15);

    @Test
    void physicalComponentReadsPhysicalResistance() {
        DefenseStats stats = DefenseStatBridge.bridge(DERIVED, DEFAULT_KEYS, DamageType.PHYSICAL);
        assertEquals(0.10, stats.resistance(), EPS);   // typed: physical
        assertEquals(0.05, stats.damageReduction(), EPS); // common
        assertEquals(3.0, stats.flatDefense(), EPS);      // common (legacy flat-defense fallback)
        // 2026-08-15: 防具値の廃止で防御率もこのブリッジが直接読む(以前はバニラ防具ミラー経由で常に0だった)。
        assertEquals(0.12, stats.defenseRate(), EPS);
        assertEquals(0.15, stats.armorStrength(), EPS);   // 防具強度(会心軽減率%): read directly here now
    }

    @Test
    void armorStrengthIsReadDirectlyAndTypeIndependent() {
        // 防具強度(会心軽減率%) is read from the derived map for both components (no longer vanilla mirror).
        DefenseStats phys = DefenseStatBridge.bridge(DERIVED, DEFAULT_KEYS, DamageType.PHYSICAL);
        DefenseStats magic = DefenseStatBridge.bridge(DERIVED, DEFAULT_KEYS, DamageType.MAGICAL);
        assertEquals(0.15, phys.armorStrength(), EPS);
        assertEquals(0.15, magic.armorStrength(), EPS);
    }

    @Test
    void magicalComponentReadsMagicalResistance() {
        DefenseStats stats = DefenseStatBridge.bridge(DERIVED, DEFAULT_KEYS, DamageType.MAGICAL);
        assertEquals(0.20, stats.resistance(), EPS);   // typed: magical
        assertEquals(0.05, stats.damageReduction(), EPS); // common, same as physical
        assertEquals(3.0, stats.flatDefense(), EPS);      // common, same as physical
    }

    @Test
    void dodgeChanceIsTypeIndependent() {
        assertEquals(0.08, DefenseStatBridge.dodgeChance(DERIVED, DEFAULT_KEYS), EPS);
    }

    @Test
    void missingKeyContributesZero() {
        DefenseStats stats = DefenseStatBridge.bridge(
                Map.of("phys-resistance", 0.3), DEFAULT_KEYS, DamageType.PHYSICAL);
        assertEquals(0.3, stats.resistance(), EPS);
        assertEquals(0.0, stats.flatDefense(), EPS);
        assertEquals(0.0, stats.damageReduction(), EPS);
    }

    @Test
    void emptyDerivedMapYieldsNoMitigation() {
        DefenseStats stats = DefenseStatBridge.bridge(Map.of(), DEFAULT_KEYS, DamageType.MAGICAL);
        assertEquals(DefenseStats.NONE, stats);
        assertEquals(0.0, DefenseStatBridge.dodgeChance(Map.of(), DEFAULT_KEYS), EPS);
    }

    @Test
    void typelessBypassesEveryDefenseStat() {
        DefenseStats stats = DefenseStatBridge.bridge(DERIVED, DEFAULT_KEYS, DamageType.TYPELESS);
        assertEquals(DefenseStats.NONE, stats);
    }

    @Test
    void derivedKeysAreCanonicalizedAgainstConfiguredKeys() {
        // roll.yml authors kebab-case; a config author could spell defense-stat-keys in snake_case.
        DefenseStatKeys snakeKeys = new DefenseStatKeys(
                "phys_resistance",
                "magic_resistance",
                "phys_flat_defense",
                "magic_flat_defense",
                "defense_rate",
                "damage_reduction",
                "dodge_chance",
                "armor_strength");
        DefenseStats stats = DefenseStatBridge.bridge(
                Map.of("magic-resistance", 0.4, "flat-defense", 2.0, "defense-rate", 0.25),
                snakeKeys, DamageType.MAGICAL);
        assertEquals(0.4, stats.resistance(), EPS);
        assertEquals(2.0, stats.flatDefense(), EPS);
        assertEquals(0.25, stats.defenseRate(), EPS);
    }
}
