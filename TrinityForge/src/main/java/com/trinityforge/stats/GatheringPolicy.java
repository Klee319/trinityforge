package com.trinityforge.stats;

/**
 * Pure derivation of the "採掘/釣りの連続処理" (gathering) extra-drop count: a fractional expected-value
 * is converted to an integer draw by keeping the integer part unconditionally and adding one more with
 * probability equal to the fractional part (期待値方式). Bukkit-free so the math is unit-testable; the
 * caller supplies the uniform(0,1) sample.
 */
public final class GatheringPolicy {

    /**
     * Upper bound on any single {@link #expectedExtra} draw. A malformed/hostile config (or a stat
     * pipeline bug) that produces a huge or non-finite expected value must never translate into a
     * billions-of-iterations drop loop; this caps the blast radius at a still-generous value.
     */
    public static final int MAX_EXTRA = 256;

    private GatheringPolicy() {
    }

    /**
     * The extra item count for an {@code expected} value: {@code 0} when {@code expected <= 0} or
     * {@code expected}/{@code uniform01} is non-finite (NaN/Infinity from a bad stat pipeline), else
     * {@code floor(expected)} plus one more when {@code uniform01} falls under the fractional part of
     * {@code expected} ({@code uniform01 < expected - floor(expected)}). A sample exactly equal to the
     * fractional part does NOT trigger the extra +1 (strict {@code <}). The result is always clamped to
     * {@code [0, MAX_EXTRA]} so a runaway expected value cannot drive a multi-billion spawn loop.
     */
    public static int expectedExtra(double expected, double uniform01) {
        if (!Double.isFinite(expected) || !Double.isFinite(uniform01) || expected <= 0.0) {
            return 0;
        }
        double bounded = Math.min(expected, MAX_EXTRA);
        int whole = (int) Math.floor(bounded);
        double fraction = bounded - whole;
        int result = uniform01 < fraction ? whole + 1 : whole;
        return Math.min(result, MAX_EXTRA);
    }
}
