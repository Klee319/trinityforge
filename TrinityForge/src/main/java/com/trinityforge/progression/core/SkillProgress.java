package com.trinityforge.progression.core;

/**
 * Immutable snapshot of one skill's progression state for a player.
 *
 * <ul>
 *   <li>{@code level} — current discrete level (0-based; 0 is the starting state)</li>
 *   <li>{@code residualExp} — EXP accumulated within the current level, always {@code >= 0}</li>
 *   <li>{@code totalExp} — cumulative EXP ever applied, always {@code >= 0}; the single source
 *       of truth used by {@link XpTransitionService} to recompute level and residual</li>
 *   <li>{@code prestige} — NG+ prestige count; 0 means the player has never prestiged</li>
 *   <li>{@code maxAllowedLevel} — level cap sourced from the skill catalog ({@code > 0})</li>
 * </ul>
 */
public record SkillProgress(
        int level,
        double residualExp,
        double totalExp,
        int prestige,
        int maxAllowedLevel
) {
    public SkillProgress {
        if (level < 0) {
            throw new IllegalArgumentException("level must be >= 0: " + level);
        }
        if (!Double.isFinite(residualExp) || residualExp < 0) {
            throw new IllegalArgumentException("residualExp must be >= 0: " + residualExp);
        }
        if (!Double.isFinite(totalExp) || totalExp < 0) {
            throw new IllegalArgumentException("totalExp must be >= 0: " + totalExp);
        }
        if (prestige < 0) {
            throw new IllegalArgumentException("prestige must be >= 0: " + prestige);
        }
        if (maxAllowedLevel <= 0) {
            throw new IllegalArgumentException("maxAllowedLevel must be > 0: " + maxAllowedLevel);
        }
    }

    /** Starting state: level 0, no EXP, no prestige. */
    public static SkillProgress start(int maxAllowedLevel) {
        return new SkillProgress(0, 0.0, 0.0, 0, maxAllowedLevel);
    }
}
