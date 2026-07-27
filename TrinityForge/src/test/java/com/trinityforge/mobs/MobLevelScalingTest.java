package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MobLevelScalingTest {

    @Test
    void zeroCoefficientReturnsBaseUnchanged() {
        assertEquals(5, MobLevelScaling.effectiveLevel(5, 0.0, 100.0));
        assertEquals(5, MobLevelScaling.effectiveLevel(5, 0.0, 0.0));
    }

    @Test
    void positiveCoefficientFloorsTheDistanceContribution() {
        // floor(9.9 * 1.0) = 9
        assertEquals(1 + 9, MobLevelScaling.effectiveLevel(1, 1.0, 9.9));
    }

    @Test
    void fractionalCoefficientFloorsTheProduct() {
        // floor(10 * 0.25) = 2
        assertEquals(3 + 2, MobLevelScaling.effectiveLevel(3, 0.25, 10.0));
    }

    @Test
    void negativeDistanceIsTreatedAsZero() {
        assertEquals(4, MobLevelScaling.effectiveLevel(4, 2.0, -50.0));
    }

    @Test
    void zeroDistanceReturnsBaseUnchanged() {
        assertEquals(7, MobLevelScaling.effectiveLevel(7, 3.5, 0.0));
    }

    @Test
    void largeDistanceScalesProportionally() {
        assertEquals(0 + 10_000, MobLevelScaling.effectiveLevel(0, 1.0, 10_000.0));
    }

    @Test
    void resultNeverGoesBelowZero() {
        // A hypothetical negative coefficient (not produced by config parsing, which clamps
        // coordinate-coefficient only for non-finite values) must still clamp the floor at 0.
        assertEquals(0, MobLevelScaling.effectiveLevel(2, -1.0, 100.0));
    }

    // --- CMB-21: max-level capped overload ---

    @Test
    void cmb21DistanceLevelIsClampedToConfiguredMaxLevel() {
        // 50,000 blocks out at coefficient 0.02 would be level 0 + 1000 uncapped; must clamp to 100.
        assertEquals(100, MobLevelScaling.effectiveLevel(0, 0.02, 50_000.0, 100));
    }

    @Test
    void cmb21ResultBelowMaxLevelIsUnaffected() {
        // floor(9.9 * 1.0) = 9; 1 + 9 = 10, well under the maxLevel=100 cap.
        assertEquals(1 + 9, MobLevelScaling.effectiveLevel(1, 1.0, 9.9, 100));
    }

    @Test
    void cmb21NegativeMaxLevelIsTreatedAsZero() {
        assertEquals(0, MobLevelScaling.effectiveLevel(5, 0.0, 0.0, -10));
    }

    @Test
    void cmb21ThreeArgOverloadRemainsUncappedForExistingCallers() {
        // Regression guard: the pre-CMB-21 3-arg overload must keep its old (uncapped) behaviour so
        // callers/tests that don't pass a maxLevel are unaffected by this change.
        assertEquals(1000, MobLevelScaling.effectiveLevel(0, 0.02, 50_000.0));
    }
}
