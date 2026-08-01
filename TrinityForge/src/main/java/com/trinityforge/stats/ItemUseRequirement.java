package com.trinityforge.stats;

/**
 * Per-item use gate authored on {@code stats/item-stats.yml} ({@code use-level-requirement} +
 * {@code use-skill}). Replaces catalog-authored use gates when present for the same material#CMD.
 *
 * <p>A skill-less row may still be authored (level only); callers resolve a default skill via
 * {@link UseSkillDefaults} before stamping. Missing level falls back to {@code 0}.
 */
public record ItemUseRequirement(int level, String skill, String role) {

    public static final ItemUseRequirement NONE = new ItemUseRequirement(0, null, null);

    public ItemUseRequirement {
        if (level < 0) {
            level = 0;
        }
        if (skill != null && skill.isBlank()) {
            skill = null;
        }
        role = normalizeRole(role);
    }

    /** ロール条件を持たない従来どおりの要件。既存の呼び出し側はこちらのまま。 */
    public ItemUseRequirement(int level, String skill) {
        this(level, skill, null);
    }

    /**
     * ロールIDは {@code progression/role-buffs.yml} のキーと同じ正規化(小文字・前後空白除去)。
     * ここで揃えておかないと「Farmer」と書いた yml が黙って一致しなくなる。
     */
    public static String normalizeRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** ロール条件が書かれているか。 */
    public boolean hasRole() {
        return role != null;
    }

    /** True when the gate has a skill and a positive level (enforced when {@code enforce} is on). */
    public boolean isPresent() {
        return hasSkill() && level > 0;
    }

    /** True when a use-skill is authored (PDC/lore stamping applies even at level 0). */
    public boolean hasSkill() {
        return skill != null && !skill.isBlank();
    }

    /**
     * True when this requirement can be stamped onto an item as-is (skill present). Level-only rows
     * need {@link UseSkillDefaults} resolution first — see {@code ItemStatsConfig#useRequirementFor}.
     */
    public boolean shouldStamp() {
        return hasSkill();
    }

    /** Level with empty/missing input treated as {@code 0}. */
    public int levelOrZero() {
        return Math.max(0, level);
    }
}
