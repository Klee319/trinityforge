package com.trinityforge.smithing;

/**
 * Pure helpers shared by {@code FurnaceSmeltListener} (smithing.yml A-1/A-2/A-3「精錬速度」/
 * B-1/B-2/B-3「精錬ボーナス」): cook-time reduction and the extra-drop percent roll, both scaled down
 * by {@link com.trinityforge.config.domains.SmithingGimmickConfig#autoModeMultiplier} when the
 * furnace was last fed by a hopper (automated) rather than a player's own hand. Bukkit-free so both
 * are unit-testable with fixed inputs, same style as {@link com.trinityforge.mining.MiningGimmickPolicy}.
 */
public final class FurnaceSmeltPolicy {

    private FurnaceSmeltPolicy() {
    }

    /**
     * Resolves the effective reduction/bonus percent for the current smelt, applying
     * {@code autoModeMultiplier} when {@code automated}. Non-finite/negative {@code rawPercent} yields
     * 0; the result is never negative.
     */
    public static double effectivePercent(double rawPercent, boolean automated, double autoModeMultiplier) {
        if (!Double.isFinite(rawPercent) || rawPercent <= 0.0) {
            return 0.0;
        }
        double multiplier = automated ? clamp01(autoModeMultiplier) : 1.0;
        return rawPercent * multiplier;
    }

    /**
     * New {@code totalCookTime} (ticks) after applying a speed-up percent (e.g. 30.0 = -30% cook time).
     * Clamped to a minimum of 1 tick so a generous config value can never make an item smelt instantly
     * (0-tick) or negative.
     */
    public static int reducedCookTime(int baseCookTime, double reductionPercent) {
        if (baseCookTime <= 0) {
            return baseCookTime;
        }
        double clampedPercent = Math.min(Math.max(reductionPercent, 0.0), 100.0);
        int reduced = (int) Math.round(baseCookTime * (1.0 - clampedPercent / 100.0));
        return Math.max(1, reduced);
    }

    private static double clamp01(double raw) {
        if (!Double.isFinite(raw) || raw < 0.0) {
            return 0.0;
        }
        return Math.min(raw, 1.0);
    }
}
