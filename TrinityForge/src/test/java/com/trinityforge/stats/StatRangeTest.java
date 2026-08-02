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

    @Test
    void twoArgConstructorLeavesRollContinuous() {
        StatRange range = new StatRange(0.0, 1.0);
        assertEquals(0.0, range.step(), 1e-12);
        assertEquals(0.333, range.valueAt(0.333), 1e-12);
    }

    @Test
    void stepOfOneTenthQuantizesTheRoll() {
        StatRange range = new StatRange(0.0, 1.0, 0.1);
        assertEquals(0.0, range.valueAt(0.0), 1e-12);
        assertEquals(0.3, range.valueAt(0.33), 1e-12);
        assertEquals(0.3, range.valueAt(0.29), 1e-12);
        assertEquals(1.0, range.valueAt(1.0), 1e-12);
    }

    @Test
    void stepOfOneQuantizesToIntegers() {
        StatRange range = new StatRange(200.0, 600.0, 1.0);
        assertEquals(400.0, range.valueAt(0.5), 1e-12);
        // raw = 200 + 0.5033 * 400 = 401.32 -> rounds to the 401 integer grid point.
        assertEquals(401.0, range.valueAt(0.5033), 1e-12);
    }

    @Test
    void quantizedValueNeverEscapesMinMax() {
        StatRange range = new StatRange(0.0, 1.0, 0.1);
        assertEquals(0.0, range.valueAt(-0.04), 1e-12);
        assertEquals(1.0, range.valueAt(1.04), 1e-12);
    }

    @Test
    void quantizationAvoidsFloatingPointNoise() {
        StatRange range = new StatRange(0.0, 1.0, 0.1);
        double value = range.valueAt(0.31);
        // A naive min + round(...)*step without a final rounding pass can yield 0.30000000000000004.
        assertEquals(0.3, value, 0.0);
    }
}
