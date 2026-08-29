package com.trinityforge.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpDecayAdminCommandTest {

    @Test
    @DisplayName("2h / 30m / 90s / 単位なし秒をミリ秒へ直す")
    void parsesHourMinuteSecondAndBareSeconds() {
        assertEquals(2L * 60L * 60L * 1000L,
                ExpDecayAdminCommand.parseDurationMillis("2h").orElseThrow());
        assertEquals(30L * 60L * 1000L,
                ExpDecayAdminCommand.parseDurationMillis("30m").orElseThrow());
        assertEquals(90_000L,
                ExpDecayAdminCommand.parseDurationMillis("90s").orElseThrow());
        assertEquals(3600_000L,
                ExpDecayAdminCommand.parseDurationMillis("3600").orElseThrow());
        assertEquals(2L * 60L * 60L * 1000L,
                ExpDecayAdminCommand.parseDurationMillis("2H").orElseThrow());
    }

    @Test
    @DisplayName("0・負・ゴミは拒否する")
    void rejectsZeroNegativeAndGarbage() {
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("0").isEmpty());
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("0s").isEmpty());
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("-2h").isEmpty());
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("abc").isEmpty());
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("2hours").isEmpty());
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("").isEmpty());
    }
}
