package com.trinityforge.digging;

/**
 * Pure helper shared by {@code DiggingDurabilityExpListener} (digging.yml C-1/C-2): converts a
 * player's累計シャベル耐久消費量(long)into a bonus-EXP fraction (0.0-1.0), linearly scaling from 0 up
 * to a per-node {@code capPercent} (C-1=最大50, C-2=最大25) at a rate of one percent per
 * {@code durabilityPerPercent} accumulated durability points (stats/digging-gimmick.yml
 * {@code durability-exp.durability-per-percent}). Bukkit-free so it is unit-testable with fixed
 * inputs, same style as {@link com.trinityforge.mining.MiningGimmickPolicy}.
 */
public final class DiggingDurabilityExpPolicy {

    private DiggingDurabilityExpPolicy() {
    }

    /**
     * @param accumulatedDurability cumulative shovel durability consumed (PDC counter); negative
     *                               treated as 0
     * @param durabilityPerPercent  durability points required for +1%; non-positive/non-finite yields 0
     * @param capPercent            the upper bound (%) resolved from the player's held
     *                               {@code feature:digging-durability-*} node; non-positive/non-finite
     *                               yields 0
     * @return the bonus as a fraction (e.g. {@code 0.5} = +50%), clamped to {@code [0, capPercent/100]}
     */
    public static double bonusFraction(long accumulatedDurability, double durabilityPerPercent, double capPercent) {
        if (accumulatedDurability <= 0
                || !Double.isFinite(durabilityPerPercent) || durabilityPerPercent <= 0.0
                || !Double.isFinite(capPercent) || capPercent <= 0.0) {
            return 0.0;
        }
        double percent = Math.min(capPercent, accumulatedDurability / durabilityPerPercent);
        return Math.max(0.0, percent) / 100.0;
    }
}
