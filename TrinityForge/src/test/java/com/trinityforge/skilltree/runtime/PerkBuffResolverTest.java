package com.trinityforge.skilltree.runtime;

import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.skilltree.generator.PerkNaming;
import com.trinityforge.stats.LoreLayout;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PerkBuffResolver}: the pure {@link PerkBuffResolver#compute} aggregation of an
 * unlocked-perk id set + canonical {@link SkillTree} config into attacker/defender buff maps (LD-9,
 * SKILL_TREE design section 3.2), plus the instance wiring over a fake {@link SkillPerkStatSource}. No
 * Bukkit, no reflection — the resolver's aggregation is fully offline-verifiable.
 */
class PerkBuffResolverTest {

    private static final String SKILL = "LIGHT_WEAPONS";
    private static final UUID PLAYER = UUID.randomUUID();
    private static final double EPS = 1e-9;

    /** A node carrying only the given buffs; layout fields are irrelevant to buff aggregation. */
    private static SkillNode node(String id, Map<String, Double> buffs) {
        return new SkillNode(id, "name-" + id, 10, SkillRole.MAIN, null, null, "STONE", 1,
                "effect", buffs, Map.of(), List.of(), List.of(), List.of());
    }

    private static SkillNode node(String id, Map<String, Double> buffs,
                                  Map<String, Map<String, Double>> multipliers) {
        return new SkillNode(id, "name-" + id, 10, SkillRole.MAIN, null, null, "STONE", 1,
                "effect", buffs, multipliers, Map.of(), List.of(), List.of(), List.of());
    }

    /** A one-tree config with the given nodes and (optional) prestige buffs. */
    private static SkillTree tree(Map<String, SkillNode> nodes, Map<String, Double> prestigeBuffs) {
        Prestige prestige = prestigeBuffs == null ? null
                : new Prestige(true, 100, "prestige", "effect", prestigeBuffs, Map.of(), 1);
        return new SkillTree(SKILL, "軽量武器", "DIAMOND_SWORD", "2,10", prestige, nodes);
    }

    private static String perk(String nodeId) {
        return PerkNaming.perkId(SKILL, nodeId);
    }

    @Nested
    @DisplayName("compute (pure aggregation)")
    class Compute {

        @Test
        @DisplayName("only unlocked nodes' buffs are summed; unlocked buffs route attack vs defense")
        void unlockedOnly_routedBySide() {
            SkillTree t = tree(Map.of(
                    "A", node("A", Map.of("attack-power", 0.1, "bleed-chance", 0.1)),
                    "S", node("S", Map.of("flat-defense", 5.0, "dodge-chance", 0.02))), null);

            PerkBuffs buffs = PerkBuffResolver.compute(Set.of(perk("A"), perk("S")), List.of(t));

            assertEquals(0.1, buffs.attack().get("attack_power"), EPS);
            assertEquals(0.1, buffs.attack().get("bleed_chance"), EPS);
            assertEquals(5.0, buffs.defense().get("flat_defense"), EPS);
            assertEquals(0.02, buffs.defense().get("dodge_chance"), EPS);
        }

        @Test
        @DisplayName("un-unlocked nodes are ignored entirely")
        void notUnlocked_ignored() {
            SkillTree t = tree(Map.of(
                    "A", node("A", Map.of("attack-power", 0.1)),
                    "B", node("B", Map.of("crit-chance", 0.5))), null);

            PerkBuffs buffs = PerkBuffResolver.compute(Set.of(perk("A")), List.of(t));

            assertEquals(0.1, buffs.attack().get("attack_power"), EPS);
            assertFalse(buffs.attack().containsKey("crit_chance"), "B was not unlocked");
        }

        @Test
        @DisplayName("exclusive greek: unlocking only one member contributes only that member's buff")
        void exclusiveGreek_onlyUnlockedMember() {
            SkillTree t = tree(Map.of(
                    "A-alpha-1", node("A-alpha-1", Map.of("attack-power", 0.05)),
                    "A-beta-1", node("A-beta-1", Map.of("crit-chance", 0.05))), null);

            PerkBuffs buffs = PerkBuffResolver.compute(Set.of(perk("A-alpha-1")), List.of(t));

            assertEquals(0.05, buffs.attack().get("attack_power"), EPS);
            assertFalse(buffs.attack().containsKey("crit_chance"), "beta greek was not unlocked");
        }

        @Test
        @DisplayName("multiple unlocked nodes stack additively on the same key")
        void sameKey_stacksAdditively() {
            SkillTree t = tree(Map.of(
                    "A", node("A", Map.of("attack-power", 0.1)),
                    "A-alpha-1", node("A-alpha-1", Map.of("attack-power", 0.05))), null);

            PerkBuffs buffs = PerkBuffResolver.compute(Set.of(perk("A"), perk("A-alpha-1")), List.of(t));

            assertEquals(0.15, buffs.attack().get("attack_power"), EPS);
        }

        @Test
        @DisplayName("unlocked node multipliers merge as Σ(v-1) within the same layer")
        void nodeMultipliers_sameLayerStackAsDeltas() {
            SkillTree t = tree(Map.of(
                    "A", node("A", Map.of(), Map.of(
                            "damage", Map.of("attack-power", 1.2))),
                    "B", node("B", Map.of(), Map.of(
                            "damage", Map.of("attack-power", 1.1),
                            "final", Map.of("crit-damage", 1.5)))), null);

            PerkBuffs buffs = PerkBuffResolver.compute(Set.of(perk("A"), perk("B")), List.of(t));

            assertEquals(0.3, buffs.multipliers().get("damage").get("attack_power"), EPS);
            assertEquals(0.5, buffs.multipliers().get("final").get("crit_damage"), EPS);
        }

        @Test
        @DisplayName("gathering buffs are retained as general total-stat addends")
        void gatheringBuffs_routeToGeneral() {
            SkillTree t = tree(Map.of("A", node("A", Map.of(
                    "mining-fortune", 0.25,
                    "fishing-luck", 0.5,
                    "fishing-bonus", 0.75))), null);

            PerkBuffs buffs = PerkBuffResolver.compute(Set.of(perk("A")), List.of(t));

            assertEquals(0.25, buffs.general().get("mining_fortune"), EPS);
            assertEquals(0.5, buffs.general().get("fishing_luck"), EPS);
            assertEquals(0.75, buffs.general().get("fishing_bonus"), EPS);
        }

        @Test
        @DisplayName("prestige buffs are added only when the prestige (ng1) perk is unlocked")
        void prestige_addedWhenUnlocked() {
            SkillTree t = tree(Map.of("A", node("A", Map.of("attack-power", 0.1))),
                    Map.of("attack-power", 0.15, "crit-damage", 0.3));

            String ng1 = PerkNaming.prestigePerkId(SKILL, 1);
            PerkBuffs locked = PerkBuffResolver.compute(Set.of(perk("A")), List.of(t));
            PerkBuffs prestiged = PerkBuffResolver.compute(Set.of(perk("A"), ng1), List.of(t));

            assertEquals(0.1, locked.attack().get("attack_power"), EPS);
            assertFalse(locked.attack().containsKey("crit_damage"), "prestige not unlocked yet");
            // 0.1 (node A) + 0.15 (prestige) stacked.
            assertEquals(0.25, prestiged.attack().get("attack_power"), EPS);
            assertEquals(0.3, prestiged.attack().get("crit_damage"), EPS);
        }

        @Test
        @DisplayName("disallowed / unknown buff keys are dropped, never routed into either map")
        void unknownKey_dropped() {
            SkillTree t = tree(Map.of("A", node("A", Map.of(
                    "attack-power", 0.1, "not-a-real-stat", 9.0))), null);

            PerkBuffs buffs = PerkBuffResolver.compute(Set.of(perk("A")), List.of(t));

            assertEquals(0.1, buffs.attack().get("attack_power"), EPS);
            assertFalse(buffs.attack().containsKey("not_a_real_stat"));
            assertFalse(buffs.defense().containsKey("not_a_real_stat"));
        }

        @Test
        @DisplayName("kebab-case buff keys are canonicalized to snake_case")
        void kebabKeys_canonicalized() {
            SkillTree t = tree(Map.of("A", node("A", Map.of(
                    "crit-chance", 0.05, "phys-resistance", 0.1))), null);

            PerkBuffs buffs = PerkBuffResolver.compute(Set.of(perk("A")), List.of(t));

            assertEquals(0.05, buffs.attack().get("crit_chance"), EPS);
            assertEquals(0.1, buffs.defense().get("phys_resistance"), EPS);
        }

        @Test
        @DisplayName("2026-07-23 stat-gate-overhaul: new attack/defense/general keys route via StatVocabulary")
        void newStatVocabularyKeys_routeToExpectedChannel() {
            SkillTree t = tree(Map.of("A", node("A", Map.of(
                    "bow-accuracy", 0.1,
                    "health-regen-bonus", 0.2,
                    "workbench-quality-bonus", 3.0,
                    "mana-bonus", 15.0))), null);

            PerkBuffs buffs = PerkBuffResolver.compute(Set.of(perk("A")), List.of(t));

            assertEquals(0.1, buffs.attack().get("bow_accuracy"), EPS);
            assertEquals(0.2, buffs.defense().get("health_regen_bonus"), EPS);
            assertEquals(3.0, buffs.general().get("workbench_quality_bonus"), EPS);
            assertEquals(15.0, buffs.general().get("mana_bonus"), EPS);
        }

        @Test
        @DisplayName("empty unlocked set yields EMPTY (backward-compatible zero addend)")
        void emptyUnlocked_isEmpty() {
            SkillTree t = tree(Map.of("A", node("A", Map.of("attack-power", 0.1))), null);

            PerkBuffs buffs = PerkBuffResolver.compute(Set.of(), List.of(t));

            assertTrue(buffs.attack().isEmpty());
            assertTrue(buffs.defense().isEmpty());
        }
    }

    @Nested
    @DisplayName("instance wiring over a SkillPerkStatSource")
    class Instance {

        private final SkillTree lightWeapons = tree(Map.of(
                "A", node("A", Map.of("attack-power", 0.1)),
                "S", node("S", Map.of("flat-defense", 5.0))), null);

        private PerkBuffResolver resolverFor(SkillPerkStatSource source) {
            return new PerkBuffResolver(source, () -> List.of(lightWeapons));
        }

        @Test
        @DisplayName("EMPTY source (Valhalla absent) contributes nothing on both sides")
        void emptySource_backwardCompatible() {
            PerkBuffResolver resolver = resolverFor(SkillPerkStatSource.EMPTY);

            assertTrue(resolver.attackerBuffs(PLAYER).isEmpty());
            assertTrue(resolver.defenderBuffs(PLAYER).isEmpty());
        }

        @Test
        @DisplayName("partially-unlocked source resolves only the unlocked perks' buffs")
        void partialUnlock_resolvesSubset() {
            SkillPerkStatSource source = id -> Set.of(perk("A")); // only node A unlocked
            PerkBuffResolver resolver = resolverFor(source);

            assertEquals(0.1, resolver.attackerBuffs(PLAYER).get("attack_power"), EPS);
            assertTrue(resolver.defenderBuffs(PLAYER).isEmpty(), "S (defense) was not unlocked");
        }

        @Test
        @DisplayName("both sides resolve when both an attack and a defense node are unlocked")
        void bothSidesUnlocked() {
            SkillPerkStatSource source = id -> Set.of(perk("A"), perk("S"));
            PerkBuffResolver resolver = resolverFor(source);

            assertEquals(0.1, resolver.attackerBuffs(PLAYER).get("attack_power"), EPS);
            assertEquals(5.0, resolver.defenderBuffs(PLAYER).get("flat_defense"), EPS);
        }

        @Test
        @DisplayName("runtime layer definitions drop undefined and stat-mismatched multipliers")
        void layerDefinitions_filterInvalidReferences() {
            SkillTree tree = tree(Map.of(
                    "A", node("A", Map.of(), Map.of(
                            "damage", Map.of("attack-power", 1.2),
                            "wrong", Map.of("crit-chance", 1.5),
                            "missing", Map.of("attack-power", 2.0)))), null);
            SkillPerkStatSource source = id -> Set.of(perk("A"));
            PerkBuffResolver resolver = new PerkBuffResolver(source, () -> List.of(tree), () -> List.of(
                    new LoreLayout.MultiplierLayer("damage", "Damage", "attack-power"),
                    new LoreLayout.MultiplierLayer("wrong", "Wrong", "attack-power")));

            PerkBuffs buffs = resolver.buffsFor(PLAYER);

            assertEquals(Set.of("damage"), buffs.multipliers().keySet());
            assertEquals(0.2, buffs.multipliers().get("damage").get("attack_power"), EPS);
        }

        @Test
        @DisplayName("main-hand buffs apply only when the held item's use-skill matches the tree skill")
        void mainHandBuffs_includeAttributesAndMultipliersForMatchingWeapon() {
            SkillNode heldNode = new SkillNode(
                    "A", "held", 10, SkillRole.MAIN, null, List.of(), null, "STONE", 1, "effect",
                    Map.of(), Map.of("max-health", 4.0, "attack-power", 2.0), Map.of(),
                    Map.of("damage", Map.of("attack-power", 1.25)), Map.of(),
                    List.of(), List.of(), List.of());
            SkillTree tree = tree(Map.of("A", heldNode), null);
            PerkBuffResolver resolver = new PerkBuffResolver(
                    id -> Set.of(perk("A")), () -> List.of(tree));

            // 使用スキルが tree のスキル(LIGHT_WEAPONS)に一致するアイテムのみ mainhand-buff が乗る。
            ItemStack matchItem = itemWithUseSkill(Material.DIAMOND_SWORD, SKILL);
            // 使用スキル未設定のアイテムは(素材が武器でも)発動しない — 素材推測フォールバック廃止。
            ItemStack unsetItem = mock(ItemStack.class);
            when(unsetItem.getType()).thenReturn(Material.DIAMOND_SWORD);
            PerkBuffs matched = resolver.buffsFor(PLAYER, matchItem);
            PerkBuffs unset = resolver.buffsFor(PLAYER, unsetItem);

            assertEquals(4.0, matched.attributes().get("max_health"), EPS);
            assertEquals(2.0, matched.attack().get("attack_power"), EPS);
            assertEquals(0.25, matched.multipliers().get("damage").get("attack_power"), EPS);
            assertTrue(unset.attributes().isEmpty());
            assertTrue(unset.attack().isEmpty());
            assertTrue(unset.multipliers().isEmpty());
        }

        /** use-skill PDC を持つ mock アイテム(matchesMainHandSkill の厳密一致検証用)。 */
        private static ItemStack itemWithUseSkill(Material material, String useSkill) {
            ItemStack item = mock(ItemStack.class);
            when(item.getType()).thenReturn(material);
            when(item.hasItemMeta()).thenReturn(true);
            ItemMeta meta = mock(ItemMeta.class);
            when(item.getItemMeta()).thenReturn(meta);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.get(PdcKeys.ITEM_USE_SKILL, PersistentDataType.STRING)).thenReturn(useSkill);
            return item;
        }
    }

    @Nested
    @DisplayName("setBuffsFor (armor-set-buffs migration §1)")
    class SetBuffsFor {

        private static SkillNode setBuffNode(String id, Map<Integer, Map<String, Double>> setBuffs) {
            return new SkillNode(id, "name-" + id, 10, SkillRole.MAIN, null, List.of(), null, "STONE", 1,
                    "effect", Map.of(), Map.of(), Map.of(), Map.of(), setBuffs, Map.of(), List.of(), List.of(),
                    List.of());
        }

        @Test
        @DisplayName("node-level tier selection happens before summing across nodes: a node defining only "
                + "tier 4 and a node defining only tier 3 both still contribute at 4 worn pieces")
        void perNodeTierSelection_thenSummedAcrossNodes() {
            SkillNode nodeFourOnly = setBuffNode("X", Map.of(4, Map.of("dodge-chance", 0.2)));
            SkillNode nodeThreeOnly = setBuffNode("Y", Map.of(3, Map.of("dodge-chance", 0.1)));
            SkillTree t = tree(Map.of("X", nodeFourOnly, "Y", nodeThreeOnly), null);
            PerkBuffResolver resolver = new PerkBuffResolver(
                    id -> Set.of(perk("X"), perk("Y")), () -> List.of(t));

            Map<String, Double> result = resolver.setBuffsFor(PLAYER, SKILL, 4);

            assertEquals(0.3, result.get("dodge_chance"), EPS,
                    "選択を全ノード合算後にやると、Yの唯一の定義(段3)が段4選択で消えてしまう");
        }

        @Test
        @DisplayName("only the unlocked node's set-buffs contribute")
        void onlyUnlockedNodesContribute() {
            SkillNode locked = setBuffNode("X", Map.of(3, Map.of("dodge-chance", 0.2)));
            SkillTree t = tree(Map.of("X", locked), null);
            PerkBuffResolver resolver = new PerkBuffResolver(id -> Set.of(), () -> List.of(t));

            assertTrue(resolver.setBuffsFor(PLAYER, SKILL, 4).isEmpty());
        }

        @Test
        @DisplayName("a skill with no matching tree yields empty")
        void noMatchingTree_isEmpty() {
            SkillNode node = setBuffNode("X", Map.of(3, Map.of("dodge-chance", 0.2)));
            SkillTree t = tree(Map.of("X", node), null);
            PerkBuffResolver resolver = new PerkBuffResolver(id -> Set.of(perk("X")), () -> List.of(t));

            assertTrue(resolver.setBuffsFor(PLAYER, "HEAVY_ARMOR", 4).isEmpty());
        }
    }
}
