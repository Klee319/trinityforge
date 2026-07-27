package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure checks for the dungeon entry gate (D2, Q4 = combat-level primary + optional key). */
class DungeonGatePolicyTest {

    @Test
    void levelGateBlocksBelowRequirement() {
        assertEquals(DungeonGatePolicy.Denial.UNDER_LEVEL,
                DungeonGatePolicy.evaluate(50, 49, false, false));
        assertTrue(DungeonGatePolicy.allowed(50, 50, false, false));
        assertTrue(DungeonGatePolicy.allowed(50, 80, false, false));
    }

    @Test
    void keyGateBlocksWhenKeyMissing() {
        assertEquals(DungeonGatePolicy.Denial.MISSING_KEY,
                DungeonGatePolicy.evaluate(0, 100, true, false));
        assertTrue(DungeonGatePolicy.allowed(0, 100, true, true));
    }

    @Test
    void levelCheckedBeforeKey() {
        // Under-level AND missing key -> the level denial is reported first (primary gate).
        assertEquals(DungeonGatePolicy.Denial.UNDER_LEVEL,
                DungeonGatePolicy.evaluate(50, 10, true, false));
    }

    @Test
    void bothDisabledIsOpen() {
        assertTrue(DungeonGatePolicy.allowed(0, 0, false, false));
        assertFalse(DungeonGatePolicy.evaluate(0, 0, false, false) != DungeonGatePolicy.Denial.NONE);
    }

    @Test
    void combinedGateRequiresBoth() {
        assertTrue(DungeonGatePolicy.allowed(50, 60, true, true));
        assertEquals(DungeonGatePolicy.Denial.MISSING_KEY,
                DungeonGatePolicy.evaluate(50, 60, true, false));
    }
}
