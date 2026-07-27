package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit-tests {@link StatRange}: {@code valueAt(reach) = min + reach * (max - min)}, mapping a roll
 * fraction in {@code [0,1]} onto the authored {@code {min, max}} range. Constructor enforces finite
 * bounds and {@code min <= max}.
 */
class StatRangeTest {

    @Test
    void valueAtMapsReachOntoTheRange() {
        StatRange range = new StatRange(2.0, 10.0);
        assertEquals(2.0, range.valueAt(0.0), 1e-12);
        assertEquals(6.0, range.valueAt(0.5), 1e-12);
        assertEquals(10.0, range.valueAt(1.0), 1e-12);
    }

    @Test
    void aZeroWidthRangeAlwaysYieldsTheSameValue() {
        StatRange range = new StatRange(3.0, 3.0);
        assertEquals(3.0, range.valueAt(0.0), 1e-12);
        assertEquals(3.0, range.valueAt(1.0), 1e-12);
    }

    @Test
    void negativeBoundsAreSupported() {
        StatRange range = new StatRange(-4.0, 4.0);
        assertEquals(0.0, range.valueAt(0.5), 1e-12);
    }

    @Test
    void constructorRejectsMinGreaterThanMax() {
        assertThrows(IllegalArgumentException.class, () -> new StatRange(5.0, 1.0));
    }

    @Test
    void constructorRejectsNonFiniteBounds() {
        assertThrows(IllegalArgumentException.class, () -> new StatRange(Double.NaN, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new StatRange(0.0, Double.POSITIVE_INFINITY));
    }
}
