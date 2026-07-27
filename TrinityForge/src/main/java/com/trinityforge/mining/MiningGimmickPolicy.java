package com.trinityforge.mining;

/**
 * Pure helper shared by the flag/percent mining-gimmick dedicated-effect listeners
 * ({@code VeinMiningListener}, {@code MiningGimmickListener}): a cooldown-elapsed check. Bukkit-free
 * so it is unit-testable with fixed inputs instead of a live clock. (2026-07-25:
 * {@code haste-active-mining}'s cooldown moved to the shared
 * {@code com.trinityforge.active.CooldownManager} — see {@code HasteActiveSkill} — but this class'
 * {@link #cooldownReady} logic remains the reference the framework's inline equivalent follows.)
 *
 * <p><b>2026-07-27: {@code percentRoll} removed</b>. It expected a 0-100 percent scale, but every
 * stat key it was fed ({@code suspicious-respawn-chance}, {@code food-save-chance}, and formerly
 * {@code hive-harvest-fortune}) is registered in {@link com.trinityforge.stats.PercentStatNormalize}'s
 * {@code RATE_KEYS} and therefore already coerced to a {@code [0,1]} fraction at aggregation time —
 * feeding that fraction back into a helper that divides by 100 again silently shrank the effective
 * chance to 1/100th of the configured value. All three sites now compare the fraction directly against
 * the roll instead ({@link com.trinityforge.combat.CritResolver} /
 * {@code BreedingBonusListener#BREEDING_EXTRA_CHILD_CHANCE} idiom:
 * {@code Double.isFinite(x) && x > 0.0 && roll < Math.min(1.0, x)}). Do not reintroduce a shared
 * "percent roll" helper without also fixing at the call site whether the input is already a fraction —
 * that mismatch is exactly what caused this bug.
 */
public final class MiningGimmickPolicy {

    private MiningGimmickPolicy() {
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
