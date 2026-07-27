package com.trinityforge.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MiningGimmickPolicy}: cooldown pure helper.
 *
 * <p>2026-07-27: {@code percentRoll} and its tests were removed here (see the class Javadoc on
 * {@link MiningGimmickPolicy} for why) — the fraction-vs-percent double-scaling bug it caused is now
 * covered directly at the call sites in {@code MiningGimmickListenerTest} and
 * {@code FoodGimmickListenerTest}.
 */
class MiningGimmickPolicyTest {

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
