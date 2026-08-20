package com.trinityforge.mobs;

import com.trinityforge.combat.DefenseStats;
import com.trinityforge.mobs.ConversionPolicy.DefenseRamp;
import com.trinityforge.mobs.ConversionPolicy.Ramp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for level handling and raw signed stat preservation.
 */
class ConversionPolicyTest {

    private static final double DELTA = 1.0e-9;

    @Test
    @DisplayName("Ramp.at clamps negative level to 0 (at(-5) == at(0))")
    void rampNegativeLevelClampsToZero() {
        Ramp r = new Ramp(2.0, 0.5);
        assertEquals(r.at(0), r.at(-5), DELTA);
        assertEquals(2.0, r.at(-5), DELTA); // base only, per-level contribution clamped away
    }

    @Test
    @DisplayName("DefenseRamp.at preserves negative resistance/flat/armor outputs")
    void defenseRampPreservesNegativeOutputs() {
        Ramp neg = new Ramp(-0.5, 0.0);
        DefenseRamp ramp = new DefenseRamp(neg, neg, neg, neg);

        DefenseStats stats = ramp.at(3, -10.0);

        assertEquals(-0.5, stats.defenseRate(), DELTA);
        assertEquals(-0.5, stats.resistance(), DELTA);
        assertEquals(-0.5, stats.damageReduction(), DELTA);
        assertEquals(-0.5, stats.flatDefense(), DELTA);
        assertEquals(-10.0, stats.armorStrength(), DELTA);
    }

    @Test
    @DisplayName("DefenseRamp.at preserves values above one until the shared combat clamp")
    void defenseRampPreservesPositiveOverflow() {
        Ramp hot = new Ramp(0.8, 0.1);
        Ramp zero = new Ramp(0.0, 0.0);
        // defense-rate = 0.8 + 0.1*5 = 1.3 -> clamp 1.0; flat-defense same raw -> NOT clamped to 1.
        DefenseRamp ramp = new DefenseRamp(hot, hot, hot, hot);

        DefenseStats stats = ramp.at(5, 2.0);

        assertEquals(1.3, stats.defenseRate(), DELTA);
        assertEquals(1.3, stats.resistance(), DELTA);
        assertEquals(1.3, stats.damageReduction(), DELTA);
        assertEquals(1.3, stats.flatDefense(), DELTA);
        assertEquals(2.0, stats.armorStrength(), DELTA);
        // sanity: a genuine zero ramp stays zero
        assertEquals(0.0, new DefenseRamp(zero, zero, zero, zero).at(5, 0.0).defenseRate(), DELTA);
    }

    @Test
    @DisplayName("Ramp 2-arg/4-arg back-compat constructors never trigger the high-level breakpoint")
    void rampBackCompatConstructorsHaveNoHighLevelBreakpoint() {
        Ramp linear = new Ramp(10.0, 2.0);
        Ramp geometric = new Ramp(10.0, 2.0, 1.05, 1.0);
        for (int level : new int[] {0, 1, 45, 60, 80, 100, 1000}) {
            assertEquals(10.0 + 2.0 * Math.max(0, level), linear.at(level), DELTA);
            assertEquals((10.0 + 2.0 * Math.max(0, level)) * Math.pow(1.05, Math.max(0, level)),
                    geometric.at(level), DELTA);
        }
    }

    @Test
    @DisplayName("Ramp high-level breakpoint contributes exactly 0 at the threshold level itself")
    void rampHighLevelBreakpointIsContinuousAtThreshold() {
        Ramp r = new Ramp(100.0, 0.0, 1.0, 1.0, 45.0, 50.0);
        assertEquals(100.0, r.at(45), DELTA, "at() at exactly the breakpoint must equal the base curve");
        assertEquals(100.0, r.at(44), DELTA, "below the breakpoint must be completely unaffected");
        assertEquals(100.0, r.at(0), DELTA, "far below the breakpoint must be completely unaffected");
    }

    @Test
    @DisplayName("Ramp high-level breakpoint adds highLevelPerLevel per level strictly above the threshold")
    void rampHighLevelBreakpointAddsLinearlyAboveThreshold() {
        Ramp r = new Ramp(100.0, 0.0, 1.0, 1.0, 45.0, 50.0);
        assertEquals(100.0 + 50.0 * 15, r.at(60), DELTA);
        assertEquals(100.0 + 50.0 * 35, r.at(80), DELTA);
    }

    @Test
    @DisplayName("Ramp high-level breakpoint works even when the base curve is zero at the threshold "
            + "(e.g. mob-import.yml flat-defense/defense-rate, base=0 per-level=0)")
    void rampHighLevelBreakpointWorksOnZeroBaseCurve() {
        Ramp r = new Ramp(0.0, 0.0, 1.0, 1.0, 45.0, 15.0);
        assertEquals(0.0, r.at(45), DELTA);
        assertEquals(15.0 * 5, r.at(50), DELTA, "a multiplicative term would stay 0x anything=0 here; "
                + "the additive design must not");
    }

    @Test
    @DisplayName("Ramp high-level breakpoint is a no-op when highLevelPerLevel is 0 even past the threshold")
    void rampHighLevelBreakpointZeroSlopeIsNoOp() {
        Ramp r = new Ramp(10.0, 1.0, 1.0, 1.0, 45.0, 0.0);
        assertEquals(10.0 + 1.0 * 100, r.at(100), DELTA);
    }
}
