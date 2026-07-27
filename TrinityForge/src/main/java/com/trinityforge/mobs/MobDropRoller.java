package com.trinityforge.mobs;

/**
 * Pure, Bukkit-free drop-roll logic extracted out of {@code MobTypeDropListener} so the
 * probability/count math is unit-testable without a live {@code Random}: the caller supplies an
 * already-generated random sample and this class only does the deterministic decision/mapping.
 */
public final class MobDropRoller {

    /** No drop table needs a stack size beyond this; also the overflow-safety bound for {@code max}. */
    private static final int MAX_STACK_COUNT = 1_000_000;

    private MobDropRoller() {
    }

    /**
     * Whether a roll with the given {@code chance} [0,1] hits, given a uniform sample in [0,1).
     * {@code chance <= 0} never hits, {@code chance >= 1} always hits (strict {@code <} comparison).
     */
    public static boolean rolls(double chance, double randomDouble0To1) {
        return randomDouble0To1 < chance;
    }

    /**
     * Picks a stack count in {@code [min, max]} inclusive from an arbitrary random int (any sign/
     * magnitude, e.g. {@code Random#nextInt()}). {@code min == max} always returns that value
     * regardless of the random input.
     *
     * <p>{@code range = max - min + 1} is computed as a {@code long} to avoid the int overflow
     * that {@code max - min + 1} would hit at {@code min=0, max=Integer.MAX_VALUE} (range would
     * wrap to {@code Integer.MIN_VALUE}, a negative modulus). {@code max} is additionally clamped
     * to {@link #MAX_STACK_COUNT} first — no real drop table needs a range anywhere near
     * {@code Integer.MAX_VALUE}, and clamping keeps the result a sane inventory stack size instead
     * of merely avoiding the crash.
     */
    public static int rollCount(int min, int max, int randomInt) {
        // max(min, ...) guards the pathological case min itself exceeds MAX_STACK_COUNT, which
        // would otherwise leave boundedMax < min and make the range negative.
        int boundedMax = Math.max(min, Math.min(max, MAX_STACK_COUNT));
        long range = (long) boundedMax - (long) min + 1L;
        long offset = Math.floorMod((long) randomInt, range);
        return (int) (min + offset);
    }
}
