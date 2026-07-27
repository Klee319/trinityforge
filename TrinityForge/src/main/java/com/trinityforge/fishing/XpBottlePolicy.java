package com.trinityforge.fishing;

/**
 * Pure helpers for {@code xp-bottle-store-unlock} (enchanting.yml B-3): converting a player's
 * level+progress into a total XP-point count, and clamping how much of that total gets stored into
 * a bottle. Bukkit-event-free so both are unit-testable with fixed inputs.
 *
 * <p>{@link #totalExperience(int, float)} uses the standard vanilla level-to-total-XP formula
 * (the same one Bukkit's own {@code Player#giveExp}/{@code #getExpToLevel} math is built on), so a
 * caller can round-trip: read {@code totalExperience(level, exp)}, subtract the stored amount, then
 * reset the player to level/exp 0 and call {@code player.giveExp(remaining)} to land back on the
 * correct level+progress without hand-rolling the piecewise curve at the call site.
 */
public final class XpBottlePolicy {

    private XpBottlePolicy() {
    }

    /**
     * Total XP points needed to reach the given {@code level} exactly (no fractional progress).
     * Vanilla's three-piece formula: quadratic below 16, a gentler quadratic 16-31, steeper above.
     */
    public static int totalExperienceAtLevel(int level) {
        if (level < 0) {
            return 0;
        }
        if (level <= 15) {
            return level * level + 6 * level;
        }
        if (level <= 30) {
            return (int) Math.round(2.5 * level * level - 40.5 * level + 360);
        }
        return (int) Math.round(4.5 * level * level - 162.5 * level + 2220);
    }

    /** XP points needed to go from {@code level} to {@code level + 1} (the vanilla "bar length"). */
    public static int experienceToNextLevel(int level) {
        if (level < 0) {
            return experienceToNextLevel(0);
        }
        if (level <= 15) {
            return 2 * level + 7;
        }
        if (level <= 30) {
            return 5 * level - 38;
        }
        return 9 * level - 158;
    }

    /**
     * Total accumulated XP points for a player currently at {@code level} with fractional
     * {@code exp} progress toward the next level (Bukkit's {@code Player#getExp()}, {@code [0,1)}).
     * Non-finite/negative {@code exp} is treated as 0 progress (defensive; never throws).
     */
    public static int totalExperience(int level, float exp) {
        double safeExp = (Float.isFinite(exp) && exp > 0.0f) ? Math.min(exp, 1.0f) : 0.0;
        return totalExperienceAtLevel(level) + (int) Math.round(safeExp * experienceToNextLevel(level));
    }

    /**
     * How much of {@code available} total XP points to store into one bottle, given the configured
     * {@code configuredAmount}: never more than the player actually has, never negative. A
     * non-positive {@code available} or {@code configuredAmount} yields 0 (no-op — nothing to store,
     * caller should not touch the player's XP or spawn a filled bottle).
     */
    public static int clampStoreAmount(int available, int configuredAmount) {
        if (available <= 0 || configuredAmount <= 0) {
            return 0;
        }
        return Math.min(available, configuredAmount);
    }
}
