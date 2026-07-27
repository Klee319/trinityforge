package com.trinityforge.skilltree.effects;

import com.trinityforge.skilltree.DedicatedEffectEntry;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DedicatedEffectGateIndex} derives the fork-facing {@code target -> perkId(s)} maps (2026-07-23
 * 動的ID方式改修) from every loaded tree's node placements, resolved purely via {@link GateEffectId#parse}
 * — no catalog file involved. Verifies the sample from the original brief: a MINING B node placing
 * {@code glyph:light} must show up in {@code glyphGatePerks()["light"]} as {@code mining_perk_b}.
 */
class DedicatedEffectGateIndexTest {

    private static SkillNode node(String id, List<DedicatedEffectEntry> effects) {
        return new SkillNode(id, id, 10, SkillRole.MAIN, null, null, "STONE", 1, "desc",
                Map.of(), Map.of(), List.of(), List.of(), effects);
    }

    @Test
    void glyphGatePerksMapsMiningBToLight() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("B", node("B", List.of(new DedicatedEffectEntry("glyph:light", null))));
        SkillTree tree = new SkillTree("MINING", "採掘", null, "2,10", null, nodes);

        DedicatedEffectGateIndex index = DedicatedEffectGateIndex.build(List.of(tree));

        assertEquals(Set.of("mining_perk_b"), index.glyphGatePerks().get("light"));
        assertTrue(index.recipeGatePerks().isEmpty());
        assertTrue(index.ritualGatePerks().isEmpty());
        assertTrue(index.dropGatePerks().isEmpty());
        assertTrue(index.flagPerks().isEmpty());
    }

    @Test
    void recipeRitualDropAndFlagChannelsRouteToTheirOwnBucket() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("D1", node("D1", List.of(new DedicatedEffectEntry("recipe:waystone_craft", null))));
        nodes.put("D2", node("D2", List.of(new DedicatedEffectEntry("ritual:animal_summon", null))));
        nodes.put("D3", node("D3", List.of(new DedicatedEffectEntry("feature:vein-mining", null))));
        nodes.put("D4", node("D4", List.of(new DedicatedEffectEntry("drop:mining:tier1", null))));
        SkillTree tree = new SkillTree("ARS_SMITHING", "Ars鍛冶", null, "2,10", null, nodes);

        DedicatedEffectGateIndex index = DedicatedEffectGateIndex.build(List.of(tree));

        assertEquals(Set.of("arssmithing_perk_d1"), index.recipeGatePerks().get("waystone_craft"));
        assertEquals(Set.of("arssmithing_perk_d2"), index.ritualGatePerks().get("animal_summon"));
        assertEquals(Set.of("arssmithing_perk_d3"), index.flagPerks().get("feature:vein-mining"));
        assertEquals(Set.of("arssmithing_perk_d4"), index.dropGatePerks().get("mining:tier1"));
    }

    @Test
    void unparseableIdContributesNothing() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("F", node("F", List.of(new DedicatedEffectEntry("light-glyph-unlock", null))));
        SkillTree tree = new SkillTree("MINING", "採掘", null, "2,10", null, nodes);

        DedicatedEffectGateIndex index = DedicatedEffectGateIndex.build(List.of(tree));

        assertTrue(index.glyphGatePerks().isEmpty());
        assertTrue(index.flagPerks().isEmpty());
        assertFalse(index.isActiveByPerks(Set.of("mining_perk_f"), "light-glyph-unlock"));
    }

    @Test
    void emptyInputsYieldEmptySingleton() {
        assertEquals(DedicatedEffectGateIndex.EMPTY, DedicatedEffectGateIndex.build(null));
        assertEquals(DedicatedEffectGateIndex.EMPTY, DedicatedEffectGateIndex.build(List.of()));
    }

    // --- effectId -> (perkId, value) runtime query index (ランタイム値クエリ層) ---

    @Test
    void isActiveByPerksTrueWhenHeldPerksContainsPlacingNode() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", List.of(new DedicatedEffectEntry("feature:vein-mining", null))));
        SkillTree tree = new SkillTree("MINING", "採掘", null, "2,10", null, nodes);

        DedicatedEffectGateIndex index = DedicatedEffectGateIndex.build(List.of(tree));

        assertTrue(index.isActiveByPerks(Set.of("mining_perk_a"), "feature:vein-mining"));
        assertFalse(index.isActiveByPerks(Set.of("mining_perk_other"), "feature:vein-mining"));
    }

    @Test
    void valueSumByPerksSumsAcrossMultipleNonUniqueNodes() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", List.of(new DedicatedEffectEntry("ars-tier", 1.0))));
        nodes.put("B", node("B", List.of(new DedicatedEffectEntry("ars-tier", 2.0))));
        SkillTree tree = new SkillTree("MINING", "採掘", null, "2,10", null, nodes);

        DedicatedEffectGateIndex index = DedicatedEffectGateIndex.build(List.of(tree));

        assertEquals(3.0, index.valueSumByPerks(Set.of("mining_perk_a", "mining_perk_b"), "ars-tier"));
        assertEquals(1.0, index.valueSumByPerks(Set.of("mining_perk_a"), "ars-tier"));
        assertEquals(0.0, index.valueSumByPerks(Set.of("mining_perk_other"), "ars-tier"));
    }

    @Test
    void unknownEffectIdOrEmptyHeldPerksYieldsFalseOrZero() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", List.of(new DedicatedEffectEntry("feature:vein-mining", null))));
        SkillTree tree = new SkillTree("MINING", "採掘", null, "2,10", null, nodes);
        DedicatedEffectGateIndex index = DedicatedEffectGateIndex.build(List.of(tree));

        assertFalse(index.isActiveByPerks(Set.of("mining_perk_a"), "no-such-effect"));
        assertEquals(0.0, index.valueSumByPerks(Set.of("mining_perk_a"), "no-such-effect"));
        assertFalse(index.isActiveByPerks(Set.of(), "feature:vein-mining"));
        assertEquals(0.0, index.valueSumByPerks(Set.of(), "feature:vein-mining"));
        assertFalse(index.isActiveByPerks(null, "feature:vein-mining"));
        assertEquals(0.0, index.valueSumByPerks(null, "feature:vein-mining"));
    }
}
