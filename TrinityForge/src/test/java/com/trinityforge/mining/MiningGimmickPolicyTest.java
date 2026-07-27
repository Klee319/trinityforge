package com.trinityforge.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MiningGimmickPolicy}: percent-roll and cooldown pure helpers. */
class MiningGimmickPolicyTest {

    @Test
    void percentRollHitsWhenRollBelowThreshold() {
        // 25% chance, roll = 0.1 -> hit.
        assertTrue(MiningGimmickPolicy.percentRoll(25.0, 0.1));
    }

    @Test
    void percentRollMissesWhenRollAtOrAboveThreshold() {
        // 25% chance, roll = 0.25 -> miss (strictly less-than threshold semantics).
        assertFalse(MiningGimmickPolicy.percentRoll(25.0, 0.25));
        assertFalse(MiningGimmickPolicy.percentRoll(25.0, 0.9));
    }

    @Test
    void zeroOrNegativePercentNeverHits() {
        assertFalse(MiningGimmickPolicy.percentRoll(0.0, 0.0));
        assertFalse(MiningGimmickPolicy.percentRoll(-10.0, 0.0));
    }

    @Test
    void nonFinitePercentNeverHits() {
        assertFalse(MiningGimmickPolicy.percentRoll(Double.NaN, 0.0));
        assertFalse(MiningGimmickPolicy.percentRoll(Double.POSITIVE_INFINITY, 0.999999));
    }

    @Test
    void percentAbove100IsTreatedAsGuaranteed() {
        assertTrue(MiningGimmickPolicy.percentRoll(150.0, 0.999));
    }

    @Test
    void cooldownReadyOnceElapsedTimeMeetsOrExceedsCooldown() {
        assertFalse(MiningGimmickPolicy.cooldownReady(1000L, 1500L, 600L));
        assertTrue(MiningGimmickPolicy.cooldownReady(1000L, 1600L, 600L));
        assertTrue(MiningGimmickPolicy.cooldownReady(1000L, 2000L, 600L));
    }

    @Test
    void negativeElapsedIsNotReady() {
        assertFalse(MiningGimmickPolicy.cooldownReady(5000L, 1000L, 600L));
    }
}
