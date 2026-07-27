package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Craft/fishing quality derivation (ITEM_ECONOMY_SPEC 5.2d): candidate skills, mode, and clamp. */
class CraftQualityPolicyTest {

    private static final Map<String, String> MAP = Map.of(
            "weapon", "SMITHING", "armor", "SMITHING", "tool", "MINING");

    @Test
    void candidateSkillsForSingleCategory() {
        assertEquals(Set.of("SMITHING"), CraftQualityPolicy.candidateSkills(Set.of("weapon"), MAP));
        assertEquals(Set.of("MINING"), CraftQualityPolicy.candidateSkills(Set.of("tool"), MAP));
    }

    @Test
    void candidateSkillsForMultiCategoryAxe() {
        // Axe = weapon+tool -> both skills are candidates (caller picks the crafter's highest).
        Set<String> categories = new LinkedHashSet<>(Set.of("weapon", "tool"));
        assertEquals(Set.of("SMITHING", "MINING"), CraftQualityPolicy.candidateSkills(categories, MAP));
    }

    @Test
    void candidateSkillsEmptyForUnmappedCategory() {
        assertTrue(CraftQualityPolicy.candidateSkills(Set.of("other"), MAP).isEmpty());
    }

    @Test
    void modeRisesEveryNLevels() {
        assertEquals(0, CraftQualityPolicy.modeFromLevel(0, 10, 0));
        assertEquals(0, CraftQualityPolicy.modeFromLevel(9, 10, 0));
        assertEquals(1, CraftQualityPolicy.modeFromLevel(10, 10, 0));
        assertEquals(3, CraftQualityPolicy.modeFromLevel(35, 10, 0));
        assertEquals(5, CraftQualityPolicy.modeFromLevel(35, 10, 2), "base offset applied");
    }

    @Test
    void modePinnedWhenLevelsPerQualityNonPositive() {
        assertEquals(2, CraftQualityPolicy.modeFromLevel(999, 0, 2));
    }

    @Test
    void resolveQualityClampsToRange() {
        assertEquals(4, CraftQualityPolicy.resolveQuality(3, 1, 9));
        assertEquals(2, CraftQualityPolicy.resolveQuality(3, -1, 9));
        assertEquals(0, CraftQualityPolicy.resolveQuality(0, -5, 9), "floored at 0");
        assertEquals(9, CraftQualityPolicy.resolveQuality(9, 5, 9), "capped at max");
    }

    // ---- mob-drop quality: normal (bell) draw around a strength-driven mode ----

    @Test
    void dropQualitySitsAtModeWhenNormalSampleIsZero() {
        // The mode is the peak of the bell: a zero standard-normal sample lands exactly on it.
        assertEquals(0, CraftQualityPolicy.resolveDropQuality(0, 0.0, 1.5, 9));
        assertEquals(3, CraftQualityPolicy.resolveDropQuality(3, 0.0, 1.5, 9));
        assertEquals(9, CraftQualityPolicy.resolveDropQuality(9, 0.0, 1.5, 9));
    }

    @Test
    void dropQualityPinnedAtModeWhenSigmaNonPositive() {
        // sigma <= 0 collapses the bell to a spike at the mode regardless of the sample.
        assertEquals(3, CraftQualityPolicy.resolveDropQuality(3, 2.0, 0.0, 9));
        assertEquals(3, CraftQualityPolicy.resolveDropQuality(3, -2.0, -1.0, 9));
    }

    @Test
    void dropQualityShiftsWithNormalSampleAndSigma() {
        // mode 4, sigma 1.5: +1σ ≈ 5.5 → rounds to 6; -1σ ≈ 2.5 → rounds to 3 (half-up).
        assertEquals(6, CraftQualityPolicy.resolveDropQuality(4, 1.0, 1.5, 9));
        assertEquals(3, CraftQualityPolicy.resolveDropQuality(4, -1.0, 1.5, 9));
    }

    @Test
    void dropQualityClampsBellAtRangeEnds() {
        // Near an end the bell is truncated (edge mass piles at the boundary → deformed distribution).
        assertEquals(9, CraftQualityPolicy.resolveDropQuality(9, 3.0, 2.0, 9), "capped at max");
        assertEquals(0, CraftQualityPolicy.resolveDropQuality(0, -3.0, 2.0, 9), "floored at 0");
        assertEquals(9, CraftQualityPolicy.resolveDropQuality(12, 0.0, 1.5, 9), "mode above max is capped");
    }

    // ---- crafted-item quality now shares the same normal (bell) draw (resolveQualityNormal) ----

    @Test
    void resolveQualityNormalMatchesDropAliasAndClamps() {
        // Craft quality now uses the same normal draw as drops; resolveDropQuality is a thin alias of it.
        for (int mode = 0; mode <= 9; mode++) {
            for (double z : new double[] {-2.0, -1.0, 0.0, 1.0, 2.0}) {
                assertEquals(
                        CraftQualityPolicy.resolveDropQuality(mode, z, 1.5, 9),
                        CraftQualityPolicy.resolveQualityNormal(mode, z, 1.5, 9),
                        "resolveDropQuality must delegate to the shared normal draw");
            }
        }
        assertEquals(3, CraftQualityPolicy.resolveQualityNormal(3, 0.0, 1.5, 9), "zero sample sits at mode");
        assertEquals(6, CraftQualityPolicy.resolveQualityNormal(4, 1.0, 1.5, 9), "+1σ rounds up");
        assertEquals(9, CraftQualityPolicy.resolveQualityNormal(9, 3.0, 2.0, 9), "capped at max");
        assertEquals(0, CraftQualityPolicy.resolveQualityNormal(0, -3.0, 2.0, 9), "floored at 0");
        assertEquals(4, CraftQualityPolicy.resolveQualityNormal(4, 5.0, 0.0, 9), "sigma 0 pins at mode");
    }

    // ---- split-normal: independent up/down spread (上振れ/下振れ を別々のσで) ----

    @Test
    void splitNormalUsesUpSigmaAboveModeAndDownSigmaBelow() {
        // mode 4, spread-up 3.0, spread-down 1.0. A +1σ sample uses the WIDE up side (4+3=7); a -1σ sample
        // uses the NARROW down side (4-1=3). A symmetric σ=1 would instead give 5 and 3, so the up-tail widens.
        assertEquals(7, CraftQualityPolicy.resolveQualityNormal(4, 1.0, 3.0, 1.0, 9), "up side uses spread-up");
        assertEquals(3, CraftQualityPolicy.resolveQualityNormal(4, -1.0, 3.0, 1.0, 9), "down side uses spread-down");
    }

    @Test
    void splitNormalWithEqualSigmasEqualsSymmetricDraw() {
        for (int mode = 0; mode <= 9; mode++) {
            for (double z : new double[] {-2.0, -1.0, 0.0, 1.0, 2.0}) {
                assertEquals(
                        CraftQualityPolicy.resolveQualityNormal(mode, z, 1.5, 9),
                        CraftQualityPolicy.resolveQualityNormal(mode, z, 1.5, 1.5, 9),
                        "equal up/down σ must reproduce the symmetric normal draw");
            }
        }
    }

    @Test
    void splitNormalZeroSampleSitsAtModeRegardlessOfSigmas() {
        // z=0 takes the up branch (>= 0) but 0 * σ = 0, so it always lands on the (clamped) mode.
        assertEquals(4, CraftQualityPolicy.resolveQualityNormal(4, 0.0, 3.0, 1.0, 9));
        assertEquals(9, CraftQualityPolicy.resolveQualityNormal(12, 0.0, 3.0, 1.0, 9), "mode above max is capped");
    }

    @Test
    void splitNormalClampsAndPinsPerChosenSide() {
        assertEquals(9, CraftQualityPolicy.resolveQualityNormal(7, 2.0, 5.0, 1.0, 9), "wide up side clamps at max");
        assertEquals(2, CraftQualityPolicy.resolveQualityNormal(4, -2.0, 5.0, 1.0, 9), "down side σ1: 4-2=2");
        assertEquals(4, CraftQualityPolicy.resolveQualityNormal(4, 3.0, 0.0, 2.0, 9), "up σ0 pins at mode");
        assertEquals(4, CraftQualityPolicy.resolveQualityNormal(4, -3.0, 2.0, 0.0, 9), "down σ0 pins at mode");
    }

    @Test
    void dropQualitySplitOverloadDelegatesToSplitNormal() {
        for (int mode = 0; mode <= 9; mode++) {
            for (double z : new double[] {-2.0, -1.0, 0.0, 1.0, 2.0}) {
                assertEquals(
                        CraftQualityPolicy.resolveQualityNormal(mode, z, 3.0, 1.0, 9),
                        CraftQualityPolicy.resolveDropQuality(mode, z, 3.0, 1.0, 9),
                        "the 5-arg resolveDropQuality must delegate to the split-normal draw");
            }
        }
    }

    // ---- minimumQuality: the true guaranteed floor of resolveQualityNormal's downward side ----

    @Test
    void minimumQualityPropertyHoldsForExtremeNegativeGaussianAcrossParamSpace() {
        // The core property (task requirement): no matter what gaussian value resolveQualityNormal is
        // fed, its result must never fall below minimumQuality(mode, spreadDown, maxQuality). Sweep an
        // extreme negative sample (-100) across a grid of (mode, spreadDown, spreadUp, maxQuality).
        double[] extremeSamples = {-100.0, -1000.0, -1.0, 0.0};
        double[] spreadDowns = {0.0, 0.5, 1.0, 1.5, 3.0, 10.0};
        double[] spreadUps = {0.0, 1.5, 5.0};
        int[] maxQualities = {0, 1, 5, 9, 15};
        for (int maxQuality : maxQualities) {
            for (int mode = 0; mode <= maxQuality; mode++) {
                for (double spreadDown : spreadDowns) {
                    for (double spreadUp : spreadUps) {
                        int floor = CraftQualityPolicy.minimumQuality(mode, spreadDown, maxQuality);
                        for (double z : extremeSamples) {
                            int drawn = CraftQualityPolicy.resolveQualityNormal(
                                    mode, z, spreadUp, spreadDown, maxQuality);
                            assertTrue(drawn >= floor,
                                    "resolveQualityNormal(mode=" + mode + ", z=" + z + ", up=" + spreadUp
                                            + ", down=" + spreadDown + ", max=" + maxQuality + ") = " + drawn
                                            + " fell below minimumQuality=" + floor);
                        }
                    }
                }
            }
        }
    }

    @Test
    void minimumQualityIsZeroWhenSpreadDownIsPositive() {
        // spreadDown > 0 lets an unbounded-negative gaussian sample reach the method's own [0,max] clamp.
        assertEquals(0, CraftQualityPolicy.minimumQuality(5, 1.5, 9));
        assertEquals(0, CraftQualityPolicy.minimumQuality(9, 0.01, 9));
        assertEquals(0, CraftQualityPolicy.minimumQuality(0, 1.5, 9));
    }

    @Test
    void minimumQualityIsModeWhenSpreadDownIsNonPositive() {
        // spreadDown <= 0 pins s=0 on the downward branch, so no gaussian sample (however negative) can
        // move the result below mode.
        assertEquals(5, CraftQualityPolicy.minimumQuality(5, 0.0, 9));
        assertEquals(5, CraftQualityPolicy.minimumQuality(5, -3.0, 9));
        assertEquals(9, CraftQualityPolicy.minimumQuality(9, 0.0, 9), "mode at max stays at max");
    }

    @Test
    void minimumQualityClampsIntoRange() {
        assertEquals(9, CraftQualityPolicy.minimumQuality(12, 0.0, 9), "mode above max clamped down");
        assertEquals(0, CraftQualityPolicy.minimumQuality(0, 5.0, 9));
    }
}
