package com.trinityforge.progression;

import java.util.Map;
import java.util.Objects;

/**
 * Pure predicate for the equip/use skill gate (PROGRESSION 1.6, gap I3): may a player whose
 * skill-tree levels are {@code playerSkillLevels} use an item that requires level
 * {@code requiredLevel} in {@code requiredSkill}?
 *
 * <p>An item with no requirement (null/blank skill or a non-positive level, matching
 * {@code ItemTemplate.hasUseRequirement}) is always usable. The requirement is single-axis (one
 * skill), which is the current item data model ({@code ItemData.useSkill}); a composite/multi-skill
 * axis is a future decision (I3). Bukkit-free so it is fully unit-testable.
 */
public final class UseRequirementPolicy {

    private UseRequirementPolicy() {
    }

    /** True when the item is unrestricted or the player meets its single-skill level requirement. */
    public static boolean meets(String requiredSkill, int requiredLevel, Map<String, Integer> playerSkillLevels) {
        Objects.requireNonNull(playerSkillLevels, "playerSkillLevels");
        if (requiredSkill == null || requiredSkill.isBlank() || requiredLevel <= 0) {
            return true;
        }
        return playerSkillLevels.getOrDefault(requiredSkill, 0) >= requiredLevel;
    }
}
