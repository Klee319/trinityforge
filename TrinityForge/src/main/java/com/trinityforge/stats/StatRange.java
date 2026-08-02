package com.trinityforge.stats;

/**
 * A per-stat roll range authored in {@code stats/item-stats.yml} under an item's {@code random} section
 * (ITEM_ECONOMY_SPEC 5.1 roll layer). The stat's rolled contribution is {@code min + reach * (max - min)},
 * where {@code reach} ∈ {@code [0, 1]} is the quality-dependent roll fraction from {@link QualityRollModel}
 * ("範囲を1として品質に応じたroll分布で抽選"). Applied ADDITIVELY on top of {@code fixed}/{@code per-quality}
 * (an undefined base counts as 0). {@code min} may exceed {@code max}? No — enforced {@code min <= max}.
 *
 * <p>{@code step} is the quantization grid the rolled value is snapped to, derived from the number of
 * decimal places authored on {@code min}/{@code max} in the yml (e.g. {@code min: 0.5, max: 2.0} implies
 * a 0.1 step; both authored as integers implies a step of 1). {@code step <= 0} means "do not quantize" —
 * the roll stays continuous, which is also the behavior of the two-arg constructor kept for compatibility
 * with existing call sites that never quantized.
 *
 * @param min the value at reach 0 (the floor of the roll)
 * @param max the value at reach 1 (the ceiling of the roll)
 * @param step the quantization grid size, or {@code <= 0} to leave the roll continuous
 */
public record StatRange(double min, double max, double step) {

    public StatRange {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("random range must be finite: min=" + min + ", max=" + max);
        }
        if (min > max) {
            throw new IllegalArgumentException("random range min (" + min + ") must be <= max (" + max + ")");
        }
        if (!Double.isFinite(step)) {
            throw new IllegalArgumentException("random range step must be finite: step=" + step);
        }
    }

    /** Compatibility constructor for callers that never quantized: {@code step <= 0} (continuous roll). */
    public StatRange(double min, double max) {
        this(min, max, 0d);
    }

    /**
     * The rolled value for a reach fraction in {@code [0, 1]}: {@code min + reach * (max - min)}, snapped
     * to the {@link #step} grid when {@code step > 0} and clamped into {@code [min, max]}. The result is
     * rounded to the decimal precision implied by {@code step} to avoid floating-point noise such as
     * {@code 0.30000000000000004}.
     */
    public double valueAt(double reach) {
        double raw = min + reach * (max - min);
        if (step <= 0) {
            return raw;
        }
        double stepsFromMin = Math.round((raw - min) / step);
        double value = min + stepsFromMin * step;
        value = Math.max(min, Math.min(max, value));
        int decimals = decimalPlaces(step);
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    /** The number of decimal places needed to exactly represent {@code v} (trailing zeros ignored), capped at 6. */
    private static int decimalPlaces(double v) {
        java.math.BigDecimal bd = java.math.BigDecimal.valueOf(v).stripTrailingZeros();
        return Math.max(0, Math.min(6, bd.scale()));
    }
}
