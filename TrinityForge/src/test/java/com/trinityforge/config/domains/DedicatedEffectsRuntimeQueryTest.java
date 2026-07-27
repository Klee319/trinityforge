package com.trinityforge.config.domains;

import com.trinityforge.pdc.PlayerData;
import com.trinityforge.skilltree.DedicatedEffectEntry;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DedicatedEffectsConfig}'s runtime query API (isActive/valueSum/valueMax + the
 * heldPerks-only variants), including the bare-feature-id back-compat normalization (2026-07-23 動的ID
 * 方式改修): a caller querying {@code isActive(player, "vein-mining")} (no {@code prefix:}) must resolve
 * against a node placing {@code feature:vein-mining}.
 */
class DedicatedEffectsRuntimeQueryTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static SkillNode node(String id, List<DedicatedEffectEntry> effects) {
        return new SkillNode(id, id, 10, SkillRole.MAIN, null, null, "STONE", 1, "desc",
                Map.of(), Map.of(), List.of(), List.of(), effects);
    }

    private static DedicatedEffectsConfig configWith(SkillTree tree) {
        DedicatedEffectsConfig config = new DedicatedEffectsConfig();
        config.reindex(List.of(tree));
        return config;
    }

    @Test
    void isActiveAndValueSumByPerksMatchWhenHeldPerksContainsPlacingNode() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", List.of(new DedicatedEffectEntry("feature:vein-mining", null))));
        SkillTree tree = new SkillTree("MINING", "採掘", null, "2,10", null, nodes);
        DedicatedEffectsConfig config = configWith(tree);

        assertTrue(config.isActiveByPerks(Set.of("mining_perk_a"), "feature:vein-mining"));
        assertFalse(config.isActiveByPerks(Set.of("some_other_perk"), "feature:vein-mining"));
        assertEquals(0.0, config.valueSumByPerks(Set.of("mining_perk_a"), "feature:vein-mining"),
                "param:none placement has no value, so the sum is 0 even when active");
    }

    @Test
    void bareFeatureIdIsNormalizedToFeaturePrefixForBackCompatCallers() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", List.of(new DedicatedEffectEntry("feature:vein-mining", null))));
        SkillTree tree = new SkillTree("MINING", "採掘", null, "2,10", null, nodes);
        DedicatedEffectsConfig config = configWith(tree);

        // Bare id (no "prefix:"), matching every pre-existing gimmick listener's isActive(player, "vein-mining")
        // call convention, must resolve the same as the fully-prefixed query.
        assertTrue(config.isActiveByPerks(Set.of("mining_perk_a"), "vein-mining"));
        assertFalse(config.isActiveByPerks(Set.of("some_other_perk"), "vein-mining"));
    }

    @Test
    void unknownEffectIdAndEmptyHeldPerksAreFalseOrZero() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", List.of(new DedicatedEffectEntry("feature:vein-mining", null))));
        SkillTree tree = new SkillTree("MINING", "採掘", null, "2,10", null, nodes);
        DedicatedEffectsConfig config = configWith(tree);

        assertFalse(config.isActiveByPerks(Set.of("mining_perk_a"), "no-such-effect"));
        assertEquals(0.0, config.valueSumByPerks(Set.of("mining_perk_a"), "no-such-effect"));
        assertFalse(config.isActiveByPerks(Set.of(), "feature:vein-mining"));
        assertFalse(config.isActiveByPerks(null, "feature:vein-mining"));
    }

    @Test
    void isActiveAndValueSumResolvePlayerHeldPerksViaPlayerDataForArsTier() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", List.of(new DedicatedEffectEntry("ars-tier", 1.0))));
        nodes.put("B", node("B", List.of(new DedicatedEffectEntry("ars-tier", 1.0))));
        SkillTree tree = new SkillTree("MINING", "採掘", null, "2,10", null, nodes);
        DedicatedEffectsConfig config = configWith(tree);

        Player player = server.addPlayer();
        PlayerData.of(player).setHeldPerks(List.of("mining_perk_a"));

        assertTrue(config.isActive(player, "ars-tier"));
        assertEquals(1.0, config.valueSum(player, "ars-tier"));

        PlayerData.of(player).setHeldPerks(List.of("mining_perk_a", "mining_perk_b"));
        assertEquals(2.0, config.valueSum(player, "ars-tier"));

        PlayerData.of(player).setHeldPerks(List.of());
        assertFalse(config.isActive(player, "ars-tier"));
        assertEquals(0.0, config.valueSum(player, "ars-tier"));
    }

    @Test
    void nullPlayerIsSafe() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", List.of(new DedicatedEffectEntry("feature:vein-mining", null))));
        SkillTree tree = new SkillTree("MINING", "採掘", null, "2,10", null, nodes);
        DedicatedEffectsConfig config = configWith(tree);

        assertFalse(config.isActive(null, "vein-mining"));
        assertEquals(0.0, config.valueSum(null, "vein-mining"));
        assertTrue(config.valueMax(null, "vein-mining").isEmpty());
    }
}
