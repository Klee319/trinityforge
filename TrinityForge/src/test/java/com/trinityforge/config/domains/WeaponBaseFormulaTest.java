package com.trinityforge.config.domains;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit-tests the {@link WeaponBaseFormula} value object: the {@code 1 + useLevel^a / b} math and its
 * defensive {@code b > 0} guard (0除算回避). The config-level {@code b <= 0 -> default} fallback is
 * covered by {@code CombatDamageConfigTest}; here the record itself must refuse a non-positive divisor.
 */
class WeaponBaseFormulaTest {

    @Test
    void baseAttackPowerMatchesFormula() {
        WeaponBaseFormula formula = new WeaponBaseFormula(true, 2.0, 1000.0);
        // 1 + 20^2 / 1000 = 1.4
        assertEquals(1.4, formula.baseAttackPower(20), 1e-9);
        // 1 + 0^2 / 1000 = 1.0 (though useLevel 0 is gated out before this is reached)
        assertEquals(1.0, formula.baseAttackPower(0), 1e-9);
    }

    @Test
    void baseAttackPowerHonorsExponentAndDivisor() {
        WeaponBaseFormula linear = new WeaponBaseFormula(true, 1.0, 10.0);
        // 1 + 30^1 / 10 = 4.0
        assertEquals(4.0, linear.baseAttackPower(30), 1e-9);
    }

    @Test
    void disabledFactoryIsNotEnabled() {
        assertFalse(WeaponBaseFormula.disabled().enabled());
    }

    @Test
    void zeroDivisorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WeaponBaseFormula(true, 2.0, 0.0));
    }

    @Test
    void negativeDivisorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WeaponBaseFormula(true, 2.0, -1.0));
    }

    @Test
    void nanDivisorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WeaponBaseFormula(true, 2.0, Double.NaN));
    }
}
