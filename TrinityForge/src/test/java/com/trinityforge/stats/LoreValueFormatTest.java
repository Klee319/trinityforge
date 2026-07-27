package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoreValueFormatTest {

    @Test
    void percentMultipliesByHundredAndAppendsSymbol() {
        assertEquals("+15.0%", LoreValueFormat.PERCENT.render(0.15, 1, true));
    }

    @Test
    void percentHonorsZeroDecimals() {
        assertEquals("+50%", LoreValueFormat.PERCENT.render(0.5, 0, true));
    }

    @Test
    void flatShowsConfiguredDecimals() {
        assertEquals("+4.5", LoreValueFormat.FLAT.render(4.5, 1, true));
    }

    @Test
    void flatWithoutSignOmitsPlus() {
        assertEquals("4.5", LoreValueFormat.FLAT.render(4.5, 1, false));
    }

    @Test
    void negativeValueKeepsMinusRegardlessOfShowSign() {
        assertEquals("-4.5", LoreValueFormat.FLAT.render(-4.5, 1, false));
    }

    @Test
    void integerRoundsToWholeNumber() {
        assertEquals("+5", LoreValueFormat.INTEGER.render(4.6, 0, true));
    }

    @Test
    void scalarPrefixesXAndNeverForcesSign() {
        assertEquals("x1.50", LoreValueFormat.SCALAR.render(1.5, 2, true));
    }

    @Test
    void percentNegativeKeepsMinusAndScalesByHundred() {
        assertEquals("-15.0%", LoreValueFormat.PERCENT.render(-0.15, 1, true));
    }

    @Test
    void integerNegativeRoundsAwayFromZero() {
        assertEquals("-5", LoreValueFormat.INTEGER.render(-4.6, 0, false));
    }

    @Test
    void scalarNegativeKeepsSignedValueAfterX() {
        assertEquals("x-1.50", LoreValueFormat.SCALAR.render(-1.5, 2, false));
    }

    @Test
    void flatZeroWithSignShowsPlusZero() {
        assertEquals("+0.0", LoreValueFormat.FLAT.render(0.0, 1, true));
    }

    @Test
    void negativeDecimalsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LoreValueFormat.FLAT.render(1.0, -1, true));
    }

    // roundsToZero: hide-when-zero must key off the rounded display value, not a fixed raw epsilon
    // (item 7 - a value that renders as "+0%" must be treated as zero even if the raw value isn't).

    @Test
    void percentTinyFractionThatRendersAsZeroRoundsToZero() {
        // 0.001 (0.1%) renders as "+0%" at decimals=0, so it must count as zero for hide-when-zero.
        assertTrue(LoreValueFormat.PERCENT.roundsToZero(0.001, 0));
    }

    @Test
    void percentValueThatStillRendersNonZeroDoesNotRoundToZero() {
        assertFalse(LoreValueFormat.PERCENT.roundsToZero(0.006, 0)); // rounds to +1%
    }

    @Test
    void flatTinyFractionAtZeroDecimalsRoundsToZero() {
        assertTrue(LoreValueFormat.FLAT.roundsToZero(0.4, 0)); // renders as "+0"
    }

    @Test
    void flatValueVisibleAtItsOwnDecimalPrecisionDoesNotRoundToZero() {
        assertFalse(LoreValueFormat.FLAT.roundsToZero(0.4, 1)); // renders as "+0.4"
    }

    @Test
    void integerRoundsToZeroWhenMagnitudeBelowHalf() {
        assertTrue(LoreValueFormat.INTEGER.roundsToZero(0.4, 0));
        assertFalse(LoreValueFormat.INTEGER.roundsToZero(0.6, 0));
    }

    @Test
    void scalarRoundsToZeroMatchesFlatBehavior() {
        assertTrue(LoreValueFormat.SCALAR.roundsToZero(0.004, 2));
        assertFalse(LoreValueFormat.SCALAR.roundsToZero(0.006, 2));
    }

    @Test
    void roundsToZeroRejectsNegativeDecimals() {
        assertThrows(IllegalArgumentException.class, () -> LoreValueFormat.FLAT.roundsToZero(1.0, -1));
    }
}
