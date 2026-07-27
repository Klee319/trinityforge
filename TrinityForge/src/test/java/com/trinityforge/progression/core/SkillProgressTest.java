package com.trinityforge.progression.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Domain boundary tests for {@link SkillProgress}. No MockBukkit required. */
class SkillProgressTest {

    @Test
    void start_hasLevelZeroAndNoExp() {
        SkillProgress s = SkillProgress.start(100);
        assertEquals(0, s.level());
        assertEquals(0L, s.residualExp());
        assertEquals(0L, s.totalExp());
        assertEquals(0, s.prestige());
        assertEquals(100, s.maxAllowedLevel());
    }

    @Test
    void validConstruction_succeeds() {
        SkillProgress s = new SkillProgress(5, 123L, 1000L, 1, 256);
        assertEquals(5, s.level());
        assertEquals(123L, s.residualExp());
        assertEquals(1000L, s.totalExp());
        assertEquals(1, s.prestige());
        assertEquals(256, s.maxAllowedLevel());
    }

    @Test
    void negativeLevel_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillProgress(-1, 0L, 0L, 0, 100));
    }

    @Test
    void negativeResidualExp_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillProgress(0, -1L, 0L, 0, 100));
    }

    @Test
    void negativeTotalExp_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillProgress(0, 0L, -1L, 0, 100));
    }

    @Test
    void negativePrestige_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillProgress(0, 0L, 0L, -1, 100));
    }

    @Test
    void zeroMaxAllowedLevel_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillProgress(0, 0L, 0L, 0, 0));
    }

    @Test
    void negativeMaxAllowedLevel_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillProgress(0, 0L, 0L, 0, -1));
    }

    @Test
    void recordEquality_byValue() {
        SkillProgress a = new SkillProgress(3, 50L, 350L, 0, 100);
        SkillProgress b = new SkillProgress(3, 50L, 350L, 0, 100);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void recordEquality_differsOnLevel() {
        SkillProgress a = new SkillProgress(3, 50L, 350L, 0, 100);
        SkillProgress b = new SkillProgress(4, 50L, 350L, 0, 100);
        assertNotEquals(a, b);
    }
}
