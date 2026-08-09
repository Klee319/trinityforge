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

    /** ドロップ増加ステ({@code mob_drop_bonus})の倍率上限。3倍を超える増幅は認めない。 */
    private static final double MAX_BONUS_FACTOR = 3.0;

    /**
     * ドロップ増加ステ({@code mob_drop_bonus}の装備+perk合算)を「個数に掛ける倍率」へ変換する。
     * 負のボーナスは無視(1.0未満にはしない)、上限は {@value #MAX_BONUS_FACTOR} 倍。
     */
    public static double bonusFactor(double mobDropBonus) {
        return Math.min(MAX_BONUS_FACTOR, 1.0 + Math.max(0.0, mobDropBonus));
    }

    /**
     * ドロップ個数へ倍率を「期待値どおり」に適用して整数化する。
     *
     * <p>単純な {@code Math.round(amount * factor)} だと +50% が1個ドロップに対して<b>常に</b>
     * 2個(切り上げ)＝実質+100%になる。整数部は確定で与え、小数部だけ確率で+1することで
     * 期待値を倍率に一致させる(1個 × 1.5 → 50%で2個 / 50%で1個)。
     *
     * <p>2026-08-09: {@code NativeSurvivalPerkListener} が持っていた同じ計算をここへ移した。
     * TF追加ドロップ側(mob-overrides / mob-level-table / mob-types)にもドロップ増加ステを
     * 掛けるようになり、実装が2か所へ分かれると片方だけ直す事故が起きるため。
     *
     * @param roll 0.0以上1.0未満の乱数。テストのために引数化している。
     */
    public static int scaleCount(int baseAmount, double dropFactor, int maxStackSize, double roll) {
        double scaled = Math.max(0.0, baseAmount) * Math.max(0.0, dropFactor);
        int whole = (int) Math.floor(scaled);
        double fraction = scaled - whole;
        int amount = whole + (fraction > 0.0 && roll < fraction ? 1 : 0);
        int cap = Math.max(1, maxStackSize) * 8;
        return Math.min(cap, Math.max(1, amount));
    }
}
