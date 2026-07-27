package com.trinityforge.gacha;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Compact-constructor validation for the gacha domain records (GachaConfig relies on these
 * throwing IllegalArgumentException to know which raw entries to skip). */
class GachaModelValidationTest {

    @Test
    void entryRejectsNonPositiveWeight() {
        assertThrows(IllegalArgumentException.class, () -> new GachaEntry("x", 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new GachaEntry("x", -1, 1, false));
    }

    @Test
    void entryRejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class, () -> new GachaEntry("x", 1, 0, false));
    }

    @Test
    void entryRejectsBlankItemId() {
        assertThrows(IllegalArgumentException.class, () -> new GachaEntry("  ", 1, 1, false));
        assertThrows(NullPointerException.class, () -> new GachaEntry(null, 1, 1, false));
    }

    @Test
    void poolRejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new GachaPool("", List.of()));
    }

    @Test
    void poolTwoArgConstructorDefaultsPityThresholdToZero() {
        GachaPool pool = new GachaPool("standard", List.of());

        assertEquals(0, pool.pityThreshold());
    }

    @Test
    void poolClampsNegativePityThresholdToZero() {
        GachaPool pool = new GachaPool("standard", List.of(), -5);

        assertEquals(0, pool.pityThreshold());
    }

    @Test
    void poolKeepsPositivePityThreshold() {
        GachaPool pool = new GachaPool("standard", List.of(), 30);

        assertEquals(30, pool.pityThreshold());
    }

    @Test
    void ticketRejectsBlankCatalogOrPoolId() {
        assertThrows(IllegalArgumentException.class, () -> new GachaTicket("", "standard"));
        assertThrows(IllegalArgumentException.class, () -> new GachaTicket("ticket", ""));
    }
}
