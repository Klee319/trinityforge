package com.trinityforge.stats;

/**
 * Unit migration for {@code stun-duration-bonus}.
 *
 * <p>Values authored before the tick-based schema used fractional values strictly between
 * {@code -1} and {@code 1} (plus the old base value {@code 0}). Each config source must be migrated
 * before aggregation; inspecting the final sum cannot distinguish a mixed new base value and an old
 * perk value. Boundary values {@code -1}/{@code 1} and larger integers are authored directly in ticks.
 */
public final class StunDurationStatNormalize {

    public static final String KEY = StatKeys.canonical("stun-duration-bonus");
    public static final double DEFAULT_TICKS = 25.0;

    private StunDurationStatNormalize() {
    }

    /** Converts one equipment/perk/role addend from the legacy fraction unit to additive ticks. */
    public static double normalizeAddend(String canonicalKey, double value) {
        if (!KEY.equals(StatKeys.canonical(canonicalKey)) || !Double.isFinite(value)) {
            return value;
        }
        double absolute = Math.abs(value);
        return absolute > 0.0 && absolute < 1.0 ? value * DEFAULT_TICKS : value;
    }

    /**
     * Converts the base-stats value. The legacy base value was a percentage on top of an implicit
     * 25-tick duration, so zero/missing becomes 25 and {@code 0.2} becomes 30.
     */
    public static double normalizeBase(double value) {
        if (!Double.isFinite(value)) {
            return DEFAULT_TICKS;
        }
        double absolute = Math.abs(value);
        if (value == 0.0) {
            return DEFAULT_TICKS;
        }
        return absolute < 1.0 ? DEFAULT_TICKS * (1.0 + value) : value;
    }
}
