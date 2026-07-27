package com.trinityforge.mobs;

import com.trinityforge.combat.DefenseStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the compact-constructor invariants of {@link MobProfile}: non-negative level and the
 * epsilon-tolerant shared-armorStrength check across components.
 */
class MobProfileTest {

    private static final double DELTA = 1.0e-9;

    private static DefenseStats defense(double armorStrength) {
        return new DefenseStats(0.0, 0.0, 0.0, 0.0, armorStrength);
    }

    @Test
    @DisplayName("negative level throws IllegalArgumentException mentioning level")
    void negativeLevelThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MobProfile("x", -1, null, defense(5.0), defense(5.0)));
        assertTrue(ex.getMessage().contains("level"));
    }

    @Test
    @DisplayName("mismatched armorStrength across components throws mentioning armorStrength")
    void mismatchedArmorStrengthThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MobProfile("x", 1, null, defense(5.0), defense(6.0)));
        assertTrue(ex.getMessage().contains("armorStrength"));
    }

    @Test
    @DisplayName("near-equal armorStrength within epsilon does not throw")
    void nearEqualArmorStrengthWithinEpsilonOk() {
        MobProfile profile = assertDoesNotThrow(
                () -> new MobProfile("x", 1, null, defense(5.0), defense(5.0 + 5.0e-10)));
        assertEquals(5.0, profile.armorStrength(), DELTA);
    }

    @Test
    @DisplayName("negative maxHealth throws IllegalArgumentException mentioning maxHealth")
    void negativeMaxHealthThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MobProfile("x", 1, null, defense(5.0), defense(5.0),
                        com.trinityforge.combat.AttackStats.plain(0), -1.0));
        assertTrue(ex.getMessage().contains("maxHealth"));
    }

    @Test
    @DisplayName("hasMaxHealth: true only for a positive maxHealth, false at 0 (unconfigured)")
    void hasMaxHealthReflectsPositiveValue() {
        MobProfile configured = new MobProfile("x", 1, null, defense(5.0), defense(5.0),
                com.trinityforge.combat.AttackStats.plain(0), 42.0);
        assertTrue(configured.hasMaxHealth());
        assertEquals(42.0, configured.maxHealth(), DELTA);

        MobProfile unconfigured = new MobProfile("x", 1, null, defense(5.0), defense(5.0));
        assertTrue(!unconfigured.hasMaxHealth());
        assertEquals(0.0, unconfigured.maxHealth(), DELTA);
    }
}
