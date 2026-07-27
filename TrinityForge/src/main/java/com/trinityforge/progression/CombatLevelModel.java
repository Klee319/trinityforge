package com.trinityforge.progression;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, config-driven combat-level mapping (DUNGEON_SPEC 2 / ADDON_INTEGRATION_SPEC 1.5 / LD-7):
 * EliteMobs combat level = the highest "pillar" score over the player's skill-tree levels, scaled
 * and clamped by a tunable curve. This is the gear-independent replacement for EliteMobs' native
 * "item tier sum" difficulty input.
 *
 * <p><b>Model (max-of-top-N, LD-7).</b> Each configured skill contributes a weighted level
 * {@code |weight| * level}. Those weighted levels are ranked, and each {@link PillarRule
 * pillar rule} {@code (top, divisor)} scores {@code sum(top highest weighted levels) / divisor}.
 * The combat level is {@code round(scale * max(pillar scores))}, clamped to
 * {@code [minLevel, maxLevel]}.
 *
 * <p>This replaces the old weighted-average model, which diluted a single-pillar specialist by the
 * other skills sitting at 0. With, e.g., pillars {@code (top=2, divisor=2.5)} and
 * {@code (top=3, divisor=3.0)}, a two-skill specialist at (100, 100) scores {@code 200/2.5 = 80}
 * while a three-skill spread at (70, 70, 70) scores {@code 210/3 = 70}. Raising the first divisor
 * (e.g. 2.5 -> higher) rates solo/specialist builds more heavily. Both the pillar rules and the
 * skill set are config-driven, so re-balancing needs no code change.
 *
 * <p>Bukkit-independent and side-effect free, so it is fully unit-testable.
 *
 * @param skillWeights immutable skill-key to weight table (which trees feed the score, and each
 *                     skill's multiplier before ranking; use 1.0 for a plain level)
 * @param pillars      ordered pillar rules; the combat level is the max score over all of them
 * @param scale        curve multiplier applied to the best pillar score
 * @param minLevel     lower clamp on the resulting combat level
 * @param maxLevel     upper clamp on the resulting combat level
 */
public record CombatLevelModel(
        Map<String, Double> skillWeights,
        List<PillarRule> pillars,
        double scale,
        int minLevel,
        int maxLevel
) {

    /**
     * One "pillar": the sum of the {@code top} highest weighted skill levels, divided by
     * {@code divisor}. Fewer trained skills than {@code top} simply sum what is available.
     *
     * @param top     how many of the highest weighted levels this pillar sums (>= 1)
     * @param divisor the divisor applied to that sum (finite, > 0); a larger divisor lowers the
     *                score, so a two-skill pillar with divisor 2.5 rates specialists below a naive
     *                average of 2
     */
    public record PillarRule(int top, double divisor) {
        public PillarRule {
            if (top < 1) {
                throw new IllegalArgumentException("pillar 'top' must be >= 1: " + top);
            }
            if (!Double.isFinite(divisor) || divisor <= 0.0) {
                throw new IllegalArgumentException(
                        "pillar 'divisor' must be finite and > 0: " + divisor);
            }
        }
    }

    public CombatLevelModel {
        Objects.requireNonNull(skillWeights, "skillWeights");
        Objects.requireNonNull(pillars, "pillars");
        // Defense-in-depth alongside CombatLevelConfig's read-side skip: a non-finite weight
        // (NaN/Inf) would make compute() rank weighted levels nonsensically, so reject it at
        // construction rather than let it silently corrupt every combat level.
        for (Map.Entry<String, Double> entry : skillWeights.entrySet()) {
            Double weight = entry.getValue();
            if (weight == null || !Double.isFinite(weight)) {
                throw new IllegalArgumentException(
                        "skill weight '" + entry.getKey() + "' must be finite: " + weight);
            }
        }
        if (!Double.isFinite(scale) || scale < 0) {
            throw new IllegalArgumentException("scale must be finite and non-negative: " + scale);
        }
        if (minLevel > maxLevel) {
            throw new IllegalArgumentException("minLevel > maxLevel: " + minLevel + " > " + maxLevel);
        }
        skillWeights = Map.copyOf(skillWeights);
        pillars = List.copyOf(pillars);
    }

    /**
     * Maps a player's skill levels to a combat level. Each configured skill contributes
     * {@code |weight| * level}; skills present in {@code skillWeights} but absent from
     * {@code skillLevels} count as level 0. Keys in {@code skillLevels} that are not configured are
     * ignored. The weighted levels are ranked, each pillar scores its top-N sum over the divisor,
     * and the best score is scaled and clamped. Returns {@link #minLevel} when there are no pillars
     * or no configured skills (best score 0).
     */
    public int compute(Map<String, Integer> skillLevels) {
        Objects.requireNonNull(skillLevels, "skillLevels");

        double[] weighted = new double[skillWeights.size()];
        int i = 0;
        for (Map.Entry<String, Double> entry : skillWeights.entrySet()) {
            double weight = Math.abs(entry.getValue());
            int level = skillLevels.getOrDefault(entry.getKey(), 0);
            weighted[i++] = weight * level;
        }
        // Ascending sort; the top-N highest are read from the tail so no extra structure is needed.
        Arrays.sort(weighted);

        double best = 0.0;
        for (PillarRule pillar : pillars) {
            int n = Math.min(pillar.top(), weighted.length);
            double sum = 0.0;
            for (int k = 0; k < n; k++) {
                sum += weighted[weighted.length - 1 - k];
            }
            double score = sum / pillar.divisor();
            if (score > best) {
                best = score;
            }
        }

        long scaled = Math.round(best * scale);
        // Clamp in long space so the result is provably within [minLevel, maxLevel] before
        // narrowing; guards against silent overflow if the bounds ever widen to long.
        long clamped = Math.max((long) minLevel, Math.min((long) maxLevel, scaled));
        return (int) clamped;
    }
}
