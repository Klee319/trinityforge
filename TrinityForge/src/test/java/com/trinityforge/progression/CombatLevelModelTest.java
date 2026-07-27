package com.trinityforge.progression;

import com.trinityforge.progression.CombatLevelModel.PillarRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CombatLevelModelTest {

    /** The shipped default pillars: pure and multi-skill builds have comparable scores. */
    private static final List<PillarRule> DEFAULT_PILLARS =
            List.of(new PillarRule(1, 1.0), new PillarRule(2, 1.5),
                    new PillarRule(3, 2.1), new PillarRule(4, 2.8));

    private static CombatLevelModel model(Map<String, Double> weights, List<PillarRule> pillars,
                                          double scale, int min, int max) {
        return new CombatLevelModel(weights, pillars, scale, min, max);
    }

    private static Map<String, Double> equalWeights(String... keys) {
        return java.util.Arrays.stream(keys)
                .collect(java.util.stream.Collectors.toMap(k -> k, k -> 1.0));
    }

    @Test
    @DisplayName("two 75-skill build reaches 100 through the top2 pillar")
    void specialist_topTwoPillarWins() {
        CombatLevelModel m = model(equalWeights("a", "b", "c", "d"), DEFAULT_PILLARS, 1.0, 0, 100);
        // top1 = 75 ; top2 = 150/1.5 = 100 ; top3 = 150/2.1 = 71.4
        assertEquals(100, m.compute(Map.of("a", 75, "b", 75, "c", 0, "d", 0)));
    }

    @Test
    @DisplayName("three 70-skill build reaches 100 through the top3 pillar")
    void spread_topThreePillarWins() {
        CombatLevelModel m = model(equalWeights("a", "b", "c"), DEFAULT_PILLARS, 1.0, 0, 100);
        // top2 = 140/1.5 = 93.3 ; top3 = 210/2.1 = 100
        assertEquals(100, m.compute(Map.of("a", 70, "b", 70, "c", 70)));
    }

    @Test
    @DisplayName("single-skill specialist is not diluted by untrained skills")
    void singleSkill_notDiluted() {
        CombatLevelModel m = model(
                equalWeights("LIGHT_WEAPONS", "HEAVY_WEAPONS", "ARCHERY", "ARS_MAGIC"),
                DEFAULT_PILLARS, 1.0, 0, 100);
        // Only ARS_MAGIC trained: top1 = 100. Untrained skills never dilute this result.
        assertEquals(100, m.compute(Map.of("ARS_MAGIC", 100)));
    }

    @Test
    @DisplayName("a larger top2 divisor rates solo/specialist builds more heavily (lower)")
    void largerDivisor_ratesSoloLower() {
        List<PillarRule> heavier = List.of(new PillarRule(2, 3.0), new PillarRule(3, 3.0));
        CombatLevelModel m = model(equalWeights("a", "b"), heavier, 1.0, 0, 100);
        // top2 = 200/3 = 66.7 ; top3 = 200/3 = 66.7 -> 67
        assertEquals(67, m.compute(Map.of("a", 100, "b", 100)));
    }

    @Test
    @DisplayName("weights multiply a skill's level before ranking")
    void weights_multiplyBeforeRanking() {
        // Single pillar (top1/1) makes the weighted level the score directly.
        CombatLevelModel m = model(Map.of("a", 3.0, "b", 1.0),
                List.of(new PillarRule(1, 1.0)), 1.0, 0, 1000);
        // weighted: a=3*40=120, b=1*90=90 -> top1 = 120
        assertEquals(120, m.compute(Map.of("a", 40, "b", 90)));
    }

    @Test
    @DisplayName("fewer trained skills than 'top' just sum what is available")
    void fewerThanTop_sumAvailable() {
        CombatLevelModel m = model(equalWeights("a", "b", "c"),
                List.of(new PillarRule(3, 3.0)), 1.0, 0, 100);
        // only 'a' trained: top3 sums (60,0,0)/3 = 20
        assertEquals(20, m.compute(Map.of("a", 60)));
    }

    @Test
    @DisplayName("unknown skills are ignored")
    void unknownSkills_ignored() {
        CombatLevelModel m = model(equalWeights("a"), List.of(new PillarRule(1, 1.0)), 1.0, 0, 100);
        assertEquals(50, m.compute(Map.of("a", 50, "b", 99)));
    }

    @Test
    @DisplayName("scale multiplies the best pillar score before clamping")
    void scale_applied() {
        CombatLevelModel m = model(equalWeights("a", "b"), DEFAULT_PILLARS, 0.5, 0, 100);
        // best = 200/1.5 = 133.3 ; *0.5 = 66.7
        assertEquals(67, m.compute(Map.of("a", 100, "b", 100)));
    }

    @Test
    @DisplayName("result is clamped to max-level")
    void clampedToMax() {
        CombatLevelModel m = model(equalWeights("a", "b"), DEFAULT_PILLARS, 1.0, 0, 50);
        assertEquals(50, m.compute(Map.of("a", 999, "b", 999)));
    }

    @Test
    @DisplayName("no configured skill trained returns min-level")
    void noSkills_returnsMin() {
        CombatLevelModel m = model(equalWeights("a"), DEFAULT_PILLARS, 1.0, 5, 100);
        assertEquals(5, m.compute(Map.of()));
    }

    @Test
    @DisplayName("no pillars returns min-level (best score 0)")
    void noPillars_returnsMin() {
        CombatLevelModel m = model(equalWeights("a"), List.of(), 1.0, 7, 100);
        assertEquals(7, m.compute(Map.of("a", 100)));
    }

    @Test
    @DisplayName("negative weights are treated as their magnitude, never flipping the score")
    void negativeWeight_usesMagnitude() {
        CombatLevelModel m = model(Map.of("a", -2.0, "b", 2.0),
                List.of(new PillarRule(1, 1.0)), 1.0, 0, 1000);
        // |−2|*30 = 60 vs |2|*10 = 20 -> top1 = 60
        assertEquals(60, m.compute(Map.of("a", 30, "b", 10)));
    }

    @Test
    @DisplayName("invalid curve parameters are rejected at construction")
    void invalidCurve_throw() {
        assertThrows(IllegalArgumentException.class,
                () -> model(equalWeights("a"), DEFAULT_PILLARS, Double.NaN, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> model(equalWeights("a"), DEFAULT_PILLARS, -1.0, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> model(equalWeights("a"), DEFAULT_PILLARS, 1.0, 100, 0));
    }

    @Test
    @DisplayName("non-finite skill weights are rejected at construction")
    void nonFiniteWeight_throw() {
        assertThrows(IllegalArgumentException.class,
                () -> model(Map.of("a", Double.NaN), DEFAULT_PILLARS, 1.0, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> model(Map.of("a", Double.POSITIVE_INFINITY), DEFAULT_PILLARS, 1.0, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> model(Map.of("a", Double.NEGATIVE_INFINITY), DEFAULT_PILLARS, 1.0, 0, 100));
    }

    @Test
    @DisplayName("invalid pillar parameters are rejected at construction")
    void invalidPillar_throw() {
        assertThrows(IllegalArgumentException.class, () -> new PillarRule(0, 2.0));
        assertThrows(IllegalArgumentException.class, () -> new PillarRule(2, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new PillarRule(2, -1.0));
        assertThrows(IllegalArgumentException.class, () -> new PillarRule(2, Double.NaN));
    }
}
