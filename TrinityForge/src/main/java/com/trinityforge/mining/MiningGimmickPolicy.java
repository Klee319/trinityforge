package com.trinityforge.mining;

/**
 * Pure helpers shared by the flag/percent mining-gimmick dedicated-effect listeners
 * ({@code VeinMiningListener}, {@code MiningGimmickListener}): a percent-chance roll and a
 * cooldown-elapsed check. Bukkit-free so both are unit-testable with fixed inputs instead of a live
 * clock/RNG. (2026-07-25: {@code haste-active-mining}'s cooldown moved to the shared
 * {@code com.trinityforge.active.CooldownManager} — see {@code HasteActiveSkill} — but this class'
 * {@link #cooldownReady} logic remains the reference the framework's inline equivalent follows.)
 */
public final class MiningGimmickPolicy {

    private MiningGimmickPolicy() {
    }

    /**
     * True with probability {@code percentValue}% (a {@code DedicatedEffectsConfig#valueSum} result,
     * expressed as 0-100, not 0-1). Floor/negative-clamped: a negative or non-finite
     * {@code percentValue} never rolls true; a value above 100 is treated as a guaranteed hit rather
     * than throwing, since a generous config value should never crash a block-break handler.
     *
     * @param roll01 a uniform draw in {@code [0, 1)}, e.g. {@code ThreadLocalRandom.current().nextDouble()}
     */
    public static boolean percentRoll(double percentValue, double roll01) {
        if (!Double.isFinite(percentValue) || percentValue <= 0.0) {
            return false;
        }
        double clamped = Math.min(percentValue, 100.0);
        return roll01 < (clamped / 100.0);
    }

    /**
     * True once at least {@code cooldownMillis} have elapsed since {@code lastUseMillis}. A negative
     * elapsed time (clock skew) is treated as "not ready" rather than throwing/underflowing.
     */
    public static boolean cooldownReady(long lastUseMillis, long nowMillis, long cooldownMillis) {
        long elapsed = nowMillis - lastUseMillis;
        return elapsed >= cooldownMillis;
    }
}
