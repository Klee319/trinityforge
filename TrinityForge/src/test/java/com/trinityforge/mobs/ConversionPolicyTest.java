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
}
