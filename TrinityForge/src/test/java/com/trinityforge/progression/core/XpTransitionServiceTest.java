package com.trinityforge.progression.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link XpTransitionService}: zero delta, positive gain, negative loss,
 * multi-level crossing, max cap, and 0-floor invariants. No MockBukkit required.
 *
 * <p>Curve used throughout: {@code cost(level) = 100 * (level + 1)}
 * → cumulative to level N = {@code 100 * N*(N+1)/2}.
 * Level 0→1: 100, 1→2: 200, 2→3: 300, 3→4: 400, 4→5: 500.
 * Cumulative: L1=100, L2=300, L3=600, L4=1000, L5=1500.
 */
class XpTransitionServiceTest {

    private static final XpCurve SIMPLE_CURVE = level -> 100L * (level + 1);
    private static final int MAX_LEVEL = 5;

    private XpTransitionService service;

    @BeforeEach
    void setUp() {
        service = new XpTransitionService(SIMPLE_CURVE);
    }

    // ---- zero delta ----

    @Test
    void zeroDelta_returnsSameReference() {
        SkillProgress s = SkillProgress.start(MAX_LEVEL);
        assertSame(s, service.apply(s, 0L));
    }

    // ---- positive gain ----

    @Test
    void residualAccumulatesWithinLevel() {
        SkillProgress s = SkillProgress.start(MAX_LEVEL);
        SkillProgress r = service.apply(s, 50L);
        assertEquals(0, r.level());
        assertEquals(50L, r.residualExp());
        assertEquals(50L, r.totalExp());
    }

    @Test
    void exactCostGainsOneLevel() {
        SkillProgress r = service.apply(SkillProgress.start(MAX_LEVEL), 100L);
        assertEquals(1, r.level());
        assertEquals(0L, r.residualExp());
        assertEquals(100L, r.totalExp());
    }

    @Test
    void slightlyOverCostGainsOneLevelWithResidue() {
        SkillProgress r = service.apply(SkillProgress.start(MAX_LEVEL), 110L);
        assertEquals(1, r.level());
        assertEquals(10L, r.residualExp());
        assertEquals(110L, r.totalExp());
    }

    @Test
    void multiLevelCrossing_exactBoundary() {
        // 100+200 = 300 → level 2 exactly
        SkillProgress r = service.apply(SkillProgress.start(MAX_LEVEL), 300L);
        assertEquals(2, r.level());
        assertEquals(0L, r.residualExp());
        assertEquals(300L, r.totalExp());
    }

    @Test
    void multiLevelCrossing_withResidue() {
        // 100+200+50 = 350 → level 2, residual 50
        SkillProgress r = service.apply(SkillProgress.start(MAX_LEVEL), 350L);
        assertEquals(2, r.level());
        assertEquals(50L, r.residualExp());
        assertEquals(350L, r.totalExp());
    }

    @Test
    void threeLevelCrossing() {
        // 100+200+300 = 600 → level 3
        SkillProgress r = service.apply(SkillProgress.start(MAX_LEVEL), 600L);
        assertEquals(3, r.level());
        assertEquals(0L, r.residualExp());
        assertEquals(600L, r.totalExp());
    }

    // ---- max cap ----

    @Test
    void maxCap_levelDoesNotExceedMax() {
        SkillProgress r = service.apply(SkillProgress.start(MAX_LEVEL), 999_999L);
        assertEquals(MAX_LEVEL, r.level());
        assertEquals(999_999L, r.totalExp());
    }

    @Test
    void maxCap_residualAccumulatesAboveCap() {
        // cumulative(5) = 1500; adding 2000 → level=5, residual=500
        SkillProgress r = service.apply(SkillProgress.start(MAX_LEVEL), 2000L);
        assertEquals(MAX_LEVEL, r.level());
        assertEquals(500L, r.residualExp());
        assertEquals(2000L, r.totalExp());
    }

    @Test
    void atMaxLevel_positiveExpDoesNotRaiseLevelFurther() {
        SkillProgress atMax = service.apply(SkillProgress.start(MAX_LEVEL), 1500L);
        assertEquals(MAX_LEVEL, atMax.level());
        SkillProgress r = service.apply(atMax, 1000L);
        assertEquals(MAX_LEVEL, r.level());
        assertEquals(2500L, r.totalExp());
    }

    // ---- 0-floor ----

    @Test
    void zeroFloor_massiveLoss_staysAtZero() {
        SkillProgress r = service.apply(SkillProgress.start(MAX_LEVEL), -999_999L);
        assertEquals(0, r.level());
        assertEquals(0L, r.residualExp());
        assertEquals(0L, r.totalExp());
    }

    @Test
    void zeroFloor_exactlyZeroAfterLoss() {
        SkillProgress at50 = service.apply(SkillProgress.start(MAX_LEVEL), 50L);
        SkillProgress r = service.apply(at50, -50L);
        assertEquals(0, r.level());
        assertEquals(0L, r.totalExp());
    }

    // ---- negative EXP ----

    @Test
    void negativeExp_losesResidualWithinLevel() {
        SkillProgress at50 = service.apply(SkillProgress.start(MAX_LEVEL), 50L);
        SkillProgress r = service.apply(at50, -20L);
        assertEquals(0, r.level());
        assertEquals(30L, r.residualExp());
        assertEquals(30L, r.totalExp());
    }

    @Test
    void negativeExp_dropsOneLevel() {
        // Gain level 1 (total=100), lose 60 → total=40, still level 0, residual=40
        SkillProgress atL1 = service.apply(SkillProgress.start(MAX_LEVEL), 100L);
        SkillProgress r = service.apply(atL1, -60L);
        assertEquals(0, r.level());
        assertEquals(40L, r.residualExp());
        assertEquals(40L, r.totalExp());
    }

    @Test
    void negativeExp_dropsMultipleLevels() {
        // Reach level 3 (total=600), lose 400 → total=200, level=1 (thresh=100,200), residual=100
        SkillProgress atL3 = service.apply(SkillProgress.start(MAX_LEVEL), 600L);
        SkillProgress r = service.apply(atL3, -400L);
        assertEquals(1, r.level());
        assertEquals(100L, r.residualExp());
        assertEquals(200L, r.totalExp());
    }

    @Test
    void negativeExp_fromMaxLevel_dropsLevel() {
        // cumulative(5)=1500; lose 200 → total=1300; L4 thresh=1000, L5 thresh=1500 → level=4, residual=300
        SkillProgress atMax = service.apply(SkillProgress.start(MAX_LEVEL), 1500L);
        SkillProgress r = service.apply(atMax, -200L);
        assertEquals(4, r.level());
        assertEquals(300L, r.residualExp());
        assertEquals(1300L, r.totalExp());
    }

    // ---- cumulative helper ----

    @Test
    void cumulativeExpForLevel_zero() {
        assertEquals(0L, service.cumulativeExpForLevel(0));
    }

    @Test
    void cumulativeExpForLevel_one() {
        assertEquals(100L, service.cumulativeExpForLevel(1));
    }

    @Test
    void cumulativeExpForLevel_three() {
        // 100 + 200 + 300 = 600
        assertEquals(600L, service.cumulativeExpForLevel(3));
    }

    @Test
    void cumulativeExpForLevel_five() {
        // 100+200+300+400+500 = 1500
        assertEquals(1500L, service.cumulativeExpForLevel(5));
    }

    // ---- prestige preservation ----

    @Test
    void prestigeIsPreservedAcrossTransition() {
        SkillProgress s = new SkillProgress(1, 0L, 100L, 2, MAX_LEVEL);
        SkillProgress r = service.apply(s, 200L);
        assertEquals(2, r.prestige());
    }

    // ---- Valhalla curve spot-check ----

    @Test
    void valhallaStandardCurve_level0_gives375() {
        XpCurve valhalla = level ->
                Math.max(1L, Math.round((level + 75.0 * Math.pow(2.0, level / 7.6)) + 300.0));
        assertEquals(375L, valhalla.expRequiredAt(0));
    }
}
