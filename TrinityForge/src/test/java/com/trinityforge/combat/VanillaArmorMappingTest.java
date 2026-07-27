package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-function checks for the vanilla armor/toughness -&gt; DefenseStats fallback mapping
 * (COMBAT_SYSTEM_SPEC 5), used when a victim has no addon PDC profile.
 */
class VanillaArmorMappingTest {

    private static final double EPS = 1e-9;

    @Test
    void mapsArmorAndToughnessLinearly() {
        DefenseStats defense = VanillaArmorMapping.toDefense(10, 4, 0.04, 0.8, 1.0);
        assertEquals(0.4, defense.defenseRate(), EPS);
        assertEquals(4.0, defense.armorStrength(), EPS);
    }

    @Test
    void defenseRateClampsAtConfiguredMax() {
        // 30 armor * 0.04/point = 1.2, clamped to 0.8.
        DefenseStats defense = VanillaArmorMapping.toDefense(30, 0, 0.04, 0.8, 1.0);
        assertEquals(0.8, defense.defenseRate(), EPS);
    }

    @Test
    void zeroArmorYieldsZeroMitigation() {
        DefenseStats defense = VanillaArmorMapping.toDefense(0, 0, 0.04, 0.8, 1.0);
        assertEquals(0.0, defense.defenseRate(), EPS);
        assertEquals(0.0, defense.armorStrength(), EPS);
    }

    @Test
    void negativeAttributeValuesAreTreatedAsZero() {
        DefenseStats defense = VanillaArmorMapping.toDefense(-5, -3, 0.04, 0.8, 1.0);
        assertEquals(0.0, defense.defenseRate(), EPS);
        assertEquals(0.0, defense.armorStrength(), EPS);
    }

    @Test
    void nonFiniteAttributeValuesAreTreatedAsZero() {
        DefenseStats defense = VanillaArmorMapping.toDefense(Double.NaN, Double.POSITIVE_INFINITY, 0.04, 0.8, 1.0);
        assertEquals(0.0, defense.defenseRate(), EPS);
        assertEquals(0.0, defense.armorStrength(), EPS);
    }

    @Test
    void resistanceAndTypedFieldsStayZero() {
        // No vanilla-armor analogue exists for resistance%/damage-reduction%/typed flat-defense
        // (COMBAT_SYSTEM_SPEC 3.3): those remain addon-PDC-only.
        DefenseStats defense = VanillaArmorMapping.toDefense(10, 4, 0.04, 0.8, 1.0);
        assertEquals(0.0, defense.resistance(), EPS);
        assertEquals(0.0, defense.damageReduction(), EPS);
        assertEquals(0.0, defense.flatDefense(), EPS);
    }
}
