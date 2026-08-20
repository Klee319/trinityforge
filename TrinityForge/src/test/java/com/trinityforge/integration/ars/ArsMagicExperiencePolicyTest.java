package com.trinityforge.integration.ars;

import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArsMagicExperiencePolicyTest {

    private static SkillCatalogEntry entry(String skill, Map<String, Double> exp) {
        return new SkillCatalogEntry(skill, 100, "1", ignored -> 1L, exp);
    }

    @Test
    void blockBreakUsesHighestMatchingGatheringBlockValue() {
        Map<String, SkillCatalogEntry> entries = Map.of(
                SkillId.MINING, entry(SkillId.MINING, Map.of("mining_break.STONE", 12.0)),
                SkillId.WOODCUTTING, entry(SkillId.WOODCUTTING,
                        Map.of("woodcutting_break.OAK_LOG", 18.0)),
                SkillId.DIGGING, entry(SkillId.DIGGING, Map.of("digging_break.DIRT", 7.0)),
                SkillId.FARMING, entry(SkillId.FARMING, Map.of("block_drops.WHEAT", 9.0)));

        assertEquals(12.0, ArsMagicExperiencePolicy.gatheringSourceExp("STONE", entries::get));
        assertEquals(18.0, ArsMagicExperiencePolicy.gatheringSourceExp("OAK_LOG", entries::get));
        assertEquals(7.0, ArsMagicExperiencePolicy.gatheringSourceExp("DIRT", entries::get));
        assertEquals(9.0, ArsMagicExperiencePolicy.gatheringSourceExp("WHEAT", entries::get));
        assertEquals(0.0, ArsMagicExperiencePolicy.gatheringSourceExp("BEDROCK", entries::get));
    }

    @Test
    void overlappingCategoriesDoNotStackAndCreateDoubleExp() {
        Map<String, SkillCatalogEntry> entries = Map.of(
                SkillId.MINING, entry(SkillId.MINING, Map.of("mining_break.STONE", 10.0)),
                SkillId.DIGGING, entry(SkillId.DIGGING, Map.of("digging_break.STONE", 8.0)));

        assertEquals(10.0, ArsMagicExperiencePolicy.gatheringSourceExp("STONE", entries::get));
    }
}
