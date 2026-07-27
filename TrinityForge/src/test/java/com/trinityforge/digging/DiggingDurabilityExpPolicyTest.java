package com.trinityforge.digging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiggingDurabilityExpPolicyTest {

    @Test
    void scalesLinearlyBelowCap() {
        // 50 durability / 100 per-percent = 0.5% -> fraction 0.005
        assertEquals(0.005, DiggingDurabilityExpPolicy.bonusFraction(50, 100.0, 50.0), 1e-9);
    }

    @Test
    void clampsAtCapPercent() {
        // 10000 durability / 100 per-percent = 100% but cap is 50%
        assertEquals(0.5, DiggingDurabilityExpPolicy.bonusFraction(10_000, 100.0, 50.0), 1e-9);
    }

    @Test
    void jobExpCapIsIndependentFromVanillaCap() {
        assertEquals(0.25, DiggingDurabilityExpPolicy.bonusFraction(10_000, 100.0, 25.0), 1e-9);
    }

    @Test
    void zeroAccumulatedYieldsZero() {
        assertEquals(0.0, DiggingDurabilityExpPolicy.bonusFraction(0, 100.0, 50.0), 1e-9);
    }

    @Test
    void nonPositiveConfigYieldsZero() {
        assertEquals(0.0, DiggingDurabilityExpPolicy.bonusFraction(100, 0.0, 50.0), 1e-9);
        assertEquals(0.0, DiggingDurabilityExpPolicy.bonusFraction(100, 100.0, 0.0), 1e-9);
    }
}
