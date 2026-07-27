package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobDropRollerTest {

    @Test
    void chanceZeroNeverRolls() {
        assertFalse(MobDropRoller.rolls(0.0, 0.0));
        assertFalse(MobDropRoller.rolls(0.0, 0.5));
    }

    @Test
    void chanceOneAlwaysRolls() {
        assertTrue(MobDropRoller.rolls(1.0, 0.0));
        assertTrue(MobDropRoller.rolls(1.0, 0.9999));
    }

    @Test
    void midChanceRollsBelowThresholdOnly() {
        assertTrue(MobDropRoller.rolls(0.5, 0.4));
        assertFalse(MobDropRoller.rolls(0.5, 0.5));
        assertFalse(MobDropRoller.rolls(0.5, 0.6));
    }

    @Test
    void rollCountEqualMinMaxAlwaysReturnsThatValue() {
        assertEquals(3, MobDropRoller.rollCount(3, 3, 0));
        assertEquals(3, MobDropRoller.rollCount(3, 3, 12345));
        assertEquals(3, MobDropRoller.rollCount(3, 3, -999));
    }

    @Test
    void rollCountStaysWithinInclusiveRange() {
        for (int sample = -20; sample <= 20; sample++) {
            int count = MobDropRoller.rollCount(1, 4, sample);
            assertTrue(count >= 1 && count <= 4, "count=" + count + " out of [1,4] for sample=" + sample);
        }
    }

    @Test
    void rollCountBoundaryRandomInputsHitBothEnds() {
        assertEquals(1, MobDropRoller.rollCount(1, 4, 0));
        assertEquals(4, MobDropRoller.rollCount(1, 4, 3));
    }

    @Test
    void rollCountHandlesNegativeRandomInput() {
        int count = MobDropRoller.rollCount(2, 5, -1);
        assertTrue(count >= 2 && count <= 5);
    }

    @Test
    void rollCountDoesNotOverflowAtIntMaxRange() {
        // min=0, max=Integer.MAX_VALUE previously computed range = max - min + 1 in int
        // arithmetic, wrapping to Integer.MIN_VALUE (a negative modulus). The fix computes the
        // range as a long and additionally clamps max to a sane stack-size bound.
        int count = MobDropRoller.rollCount(0, Integer.MAX_VALUE, 12345);
        assertTrue(count >= 0, "count=" + count + " must not be negative after overflow fix");
    }

    @Test
    void rollCountClampsAbsurdMaxToSafeStackBound() {
        int count = MobDropRoller.rollCount(0, Integer.MAX_VALUE, 0);
        assertTrue(count <= 1_000_000, "count=" + count + " should be bounded to a sane stack size");
    }

    @Test
    void rollCountStaysSaneWhenMinAloneExceedsBound() {
        // Pathological config: min itself is huge. Range must never go negative/zero-divide.
        int count = MobDropRoller.rollCount(2_000_000, Integer.MAX_VALUE, 7);
        assertTrue(count >= 2_000_000, "count=" + count + " must be >= min");
    }
}
