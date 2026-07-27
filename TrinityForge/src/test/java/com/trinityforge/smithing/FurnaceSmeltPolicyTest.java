package com.trinityforge.smithing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FurnaceSmeltPolicyTest {

    @Test
    void effectivePercentIsUnchangedForManual() {
        assertEquals(30.0, FurnaceSmeltPolicy.effectivePercent(30.0, false, 0.25), 1e-9);
    }

    @Test
    void effectivePercentIsDecayedForAutomated() {
        assertEquals(7.5, FurnaceSmeltPolicy.effectivePercent(30.0, true, 0.25), 1e-9);
    }

    @Test
    void effectivePercentZeroWhenAutoMultiplierIsZero() {
        assertEquals(0.0, FurnaceSmeltPolicy.effectivePercent(30.0, true, 0.0), 1e-9);
    }

    @Test
    void effectivePercentNonPositiveRawYieldsZero() {
        assertEquals(0.0, FurnaceSmeltPolicy.effectivePercent(0.0, false, 1.0), 1e-9);
        assertEquals(0.0, FurnaceSmeltPolicy.effectivePercent(-5.0, false, 1.0), 1e-9);
        assertEquals(0.0, FurnaceSmeltPolicy.effectivePercent(Double.NaN, false, 1.0), 1e-9);
    }

    @Test
    void reducedCookTimeAppliesPercent() {
        assertEquals(140, FurnaceSmeltPolicy.reducedCookTime(200, 30.0));
    }

    @Test
    void reducedCookTimeClampsToAtLeastOneTick() {
        assertEquals(1, FurnaceSmeltPolicy.reducedCookTime(200, 100.0));
        assertEquals(1, FurnaceSmeltPolicy.reducedCookTime(1, 99.0));
    }

    @Test
    void reducedCookTimeClampsPercentAbove100() {
        assertEquals(1, FurnaceSmeltPolicy.reducedCookTime(200, 500.0));
    }

    @Test
    void reducedCookTimeNonPositiveBaseUnchanged() {
        assertEquals(0, FurnaceSmeltPolicy.reducedCookTime(0, 30.0));
    }
}
