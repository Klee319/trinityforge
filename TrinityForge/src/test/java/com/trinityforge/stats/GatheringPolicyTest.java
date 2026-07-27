package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link GatheringPolicy#expectedExtra} — 期待値方式の追加ドロップ数導出。 */
class GatheringPolicyTest {

    @Test
    void zeroExpectedYieldsZero() {
        assertEquals(0, GatheringPolicy.expectedExtra(0.0, 0.0));
        assertEquals(0, GatheringPolicy.expectedExtra(0.0, 0.999));
    }

    @Test
    void negativeExpectedYieldsZero() {
        assertEquals(0, GatheringPolicy.expectedExtra(-1.0, 0.0));
        assertEquals(0, GatheringPolicy.expectedExtra(-0.001, 0.999));
    }

    @Test
    void wholeExpectedAlwaysYieldsThatWhole() {
        assertEquals(2, GatheringPolicy.expectedExtra(2.0, 0.0));
        assertEquals(2, GatheringPolicy.expectedExtra(2.0, 0.4999));
        assertEquals(2, GatheringPolicy.expectedExtra(2.0, 0.9999));
    }

    @Test
    void fractionalExpectedRollsExtraUnderUniformSample() {
        // expected=2.5: fraction=0.5. uniform 0.4 < 0.5 -> extra +1 -> 3.
        assertEquals(3, GatheringPolicy.expectedExtra(2.5, 0.4));
        // uniform 0.6 >= 0.5 -> no extra -> 2.
        assertEquals(2, GatheringPolicy.expectedExtra(2.5, 0.6));
    }

    @Test
    void boundaryUniformEqualToFractionDoesNotTriggerExtra() {
        // uniform == fraction exactly is NOT "<", so no +1.
        assertEquals(2, GatheringPolicy.expectedExtra(2.5, 0.5));
    }

    @Test
    void subOneExpectedRollsBetweenZeroAndOne() {
        assertEquals(1, GatheringPolicy.expectedExtra(0.3, 0.2));
        assertEquals(0, GatheringPolicy.expectedExtra(0.3, 0.5));
    }

    @Test
    void nonFiniteExpectedYieldsZero() {
        assertEquals(0, GatheringPolicy.expectedExtra(Double.NaN, 0.0));
        assertEquals(0, GatheringPolicy.expectedExtra(Double.POSITIVE_INFINITY, 0.0));
        assertEquals(0, GatheringPolicy.expectedExtra(Double.NEGATIVE_INFINITY, 0.0));
    }

    @Test
    void nonFiniteUniformSampleYieldsZero() {
        assertEquals(0, GatheringPolicy.expectedExtra(5.0, Double.NaN));
    }

    @Test
    void hugeExpectedIsClampedToMaxExtra() {
        // A malformed/hostile config or bad stat pipeline must never drive a multi-billion spawn loop.
        assertEquals(GatheringPolicy.MAX_EXTRA, GatheringPolicy.expectedExtra(Double.MAX_VALUE, 0.9999));
        assertEquals(GatheringPolicy.MAX_EXTRA, GatheringPolicy.expectedExtra(1_000_000.0, 0.0));
    }

    @Test
    void expectedExactlyAtCapYieldsCapWithNoOverflow() {
        assertEquals(GatheringPolicy.MAX_EXTRA, GatheringPolicy.expectedExtra(GatheringPolicy.MAX_EXTRA, 0.9999));
    }
}
