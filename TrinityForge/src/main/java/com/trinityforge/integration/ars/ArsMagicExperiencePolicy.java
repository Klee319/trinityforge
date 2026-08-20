package com.trinityforge.integration.ars;

import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;

import java.util.function.Function;

/** Pure classification policy for converting a magic-broken block into ARS_MAGIC EXP. */
public final class ArsMagicExperiencePolicy {

    private ArsMagicExperiencePolicy() {
    }

    /**
     * Reuses the editable gathering block values and takes the highest matching category. Taking the
     * maximum avoids double EXP if an operator accidentally lists one material in two progression files.
     */
    public static double gatheringSourceExp(
            String material, Function<String, SkillCatalogEntry> entryResolver) {
        if (material == null || entryResolver == null) return 0.0;
        return Math.max(
                exp(entryResolver.apply(SkillId.FARMING), "block_drops", material),
                Math.max(
                        exp(entryResolver.apply(SkillId.WOODCUTTING), "woodcutting_break", material),
                        Math.max(
                                exp(entryResolver.apply(SkillId.DIGGING), "digging_break", material),
                                exp(entryResolver.apply(SkillId.MINING), "mining_break", material))));
    }

    private static double exp(SkillCatalogEntry entry, String action, String material) {
        return entry == null ? 0.0 : Math.max(0.0, entry.expFor(action, material));
    }
}
