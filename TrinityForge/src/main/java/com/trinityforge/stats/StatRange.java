package com.trinityforge.stats;

/**
 * A per-stat roll range authored in {@code stats/item-stats.yml} under an item's {@code random} section
 * (ITEM_ECONOMY_SPEC 5.1 roll layer). The stat's rolled contribution is {@code min + reach * (max - min)},
 * where {@code reach} ∈ {@code [0, 1]} is the quality-dependent roll fraction from {@link QualityRollModel}
 * ("範囲を1として品質に応じたroll分布で抽選"). Applied ADDITIVELY on top of {@code fixed}/{@code per-quality}
 * (an undefined base counts as 0). {@code min} may exceed {@code max}? No — enforced {@code min <= max}.
 *
 * @param min the value at reach 0 (the floor of the roll)
 * @param max the value at reach 1 (the ceiling of the roll)
 */
public record StatRange(double min, double max) {

    public StatRange {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("random range must be finite: min=" + min + ", max=" + max);
        }
        if (min > max) {
            throw new IllegalArgumentException("random range min (" + min + ") must be <= max (" + max + ")");
        }
    }

    /** The rolled value for a reach fraction in {@code [0, 1]}: {@code min + reach * (max - min)}. */
    public double valueAt(double reach) {
        return min + reach * (max - min);
    }
}
