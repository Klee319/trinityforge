package com.trinityforge.skilltree.generator;

import com.trinityforge.skilltree.DedicatedEffectEntry;
import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit coverage for {@link SkillTreeProgressionGenerator}: coordinate determinism, the TF→Valhalla
 * mapping (required_lv / requireperk_all, native pass-through, buff exclusion), reciprocal exclusive
 * wiring, lang integrity, and the prestige (New-Game+) perk. Fixtures are built in-code so the generator
 * is exercised with no Bukkit on the classpath.
 */
class SkillTreeProgressionGeneratorTest {

    private static final Pattern LANG_REF = Pattern.compile("<lang\\.([A-Za-z0-9_]+)>");

    private static SkillNode node(String id, String name, int level, SkillRole role, String parent,
                                  String group, Map<String, Double> buffs, Map<String, Object> nativeMap) {
        return new SkillNode(id, name, level, role, parent, group, "STONE", 1,
                "effect of " + id, buffs, nativeMap, List.of(), List.of(), List.of());
    }

    /** A compact tree exercising every role, an exclusive greek group, native rewards and prestige. */
    private static SkillTree fixture() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", "鋭刃", 10, SkillRole.MAIN, null, null,
                Map.of("attack_power", 0.1), Map.of()));
        nodes.put("B", node("B", "疾風", 30, SkillRole.MAIN, "A", null,
                Map.of("crit_chance", 0.1), Map.of("lightweapons_attackspeedmultiplier_add", 0.1)));
        nodes.put("AB", node("AB", "中間", 20, SkillRole.INTERMEDIATE, "A", null,
                Map.of(), Map.of()));
        nodes.put("B-1-1", node("B-1-1", "打ち払い", 30, SkillRole.BRANCH, "B", null,
                Map.of(), Map.of("lightweapons_knockbackmultiplier_add", 0.3)));
        nodes.put("A-alpha-1", node("A-alpha-1", "剛", 10, SkillRole.GREEK, "A", "A-greek",
                Map.of("attack_power", 0.05), Map.of()));
        nodes.put("A-beta-1", node("A-beta-1", "疾", 10, SkillRole.GREEK, "A", "A-greek",
                Map.of("crit_chance", 0.05), Map.of()));
        nodes.put("A-gamma-1", node("A-gamma-1", "冴", 10, SkillRole.GREEK, "A", "A-greek",
                Map.of("crit_damage", 0.15), Map.of()));

        Prestige prestige = new Prestige(true, 100, "剣聖", "永続ボーナス",
                Map.of("attack_power", 0.15), Map.of("lightweapons_prestigebonus_add", 0.1), 1);

        return new SkillTree("LIGHT_WEAPONS", "軽量武器", "DIAMOND_SWORD", "2,10", prestige, nodes);
    }

    @Test
    @DisplayName("(a) generation is byte-deterministic and order-stable")
    void deterministicOutput() {
        SkillTree tree = fixture();

        GeneratedProgression first = SkillTreeProgressionGenerator.generate(tree);
        GeneratedProgression second = SkillTreeProgressionGenerator.generate(tree);

        assertEquals(first.yaml(), second.yaml());
        assertEquals(first.lang(), second.lang());
        assertEquals(new ArrayList<>(first.perks().keySet()), new ArrayList<>(second.perks().keySet()));
    }

    @Test
    @DisplayName("(a) coordinates follow the deterministic vertical trunk layout")
    void deterministicCoordinates() {
        GeneratedProgression gen = SkillTreeProgressionGenerator.generate(fixture());

        assertEquals("2,10", gen.startingCoordinates());
        // Synthetic lv0 root; MAIN y = centerY - (ordinal+1)*TRUNK_STEP (1 connector between parks).
        assertEquals(new Coord(2, 10), gen.perks().get("lightweapons_perk_root").coords());
        assertEquals(new Coord(2, 8), gen.perks().get("lightweapons_perk_a").coords());  // ordinal 0
        assertEquals(new Coord(2, 6), gen.perks().get("lightweapons_perk_b").coords());  // ordinal 1
        // intermediate between A(y8) and B(y6) at lv20 → y=7.
        assertEquals(new Coord(2, 7), gen.perks().get("lightweapons_perk_ab").coords());
        // Exclusive roots form a real fork on the next progression layer.
        Coord alpha = gen.perks().get("lightweapons_perk_a_alpha_1").coords();
        Coord beta = gen.perks().get("lightweapons_perk_a_beta_1").coords();
        Coord gamma = gen.perks().get("lightweapons_perk_a_gamma_1").coords();
        assertTrue(alpha.y() < gen.perks().get("lightweapons_perk_a").coords().y());
        assertEquals(3, java.util.Set.of(alpha, beta, gamma).size());
        // Branch on B also advances upward instead of extending as a horizontal rail.
        assertTrue(gen.perks().get("lightweapons_perk_b_1_1").coords().y()
                < gen.perks().get("lightweapons_perk_b").coords().y());
        // Prestige above top MAIN B (y=6): 6 - TRUNK_STEP = 4.
        assertEquals(new Coord(2, 4), gen.perks().get("lightweapons_perk_ng1").coords());

        // No two perks share a coordinate.
        long distinct = gen.perks().values().stream().map(GeneratedPerk::coords).distinct().count();
        assertEquals(gen.perks().size(), distinct, "perk coordinates must be unique");
    }

    @Test
    void anyOfMergeEmitsRequirePerkOneForEveryCandidateParent() {
        SkillNode left = new SkillNode(
                "LEFT", "left", 10, SkillRole.BRANCH, null, null, "STONE", 1, "",
                Map.of(), Map.of(), List.of(), List.of(), List.of());
        SkillNode right = new SkillNode(
                "RIGHT", "right", 10, SkillRole.BRANCH, null, null, "STONE", 1, "",
                Map.of(), Map.of(), List.of(), List.of(), List.of());
        SkillNode merge = new SkillNode(
                "MERGE", "merge", 20, SkillRole.BRANCH,
                "LEFT", List.of("RIGHT"), null, "STONE", 1, "",
                Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of());
        SkillTree tree = new SkillTree(
                "MINING", "Mining", "STONE", "2,10", null,
                Map.of("LEFT", left, "RIGHT", right, "MERGE", merge));

        GeneratedPerk generated = SkillTreeProgressionGenerator.generate(tree)
                .perks().get("mining_perk_merge");
        assertEquals(List.of("mining_perk_left", "mining_perk_right"),
                generated.requirePerkOne());
        assertTrue(generated.requirePerkAll().isEmpty());
    }

    @Test
    @DisplayName("(b) required_lv and requireperk_all map from level and parent")
    void levelAndParentMapping() {
        GeneratedProgression gen = SkillTreeProgressionGenerator.generate(fixture());

        assertEquals(10, gen.perks().get("lightweapons_perk_a").requiredLv());
        assertEquals(30, gen.perks().get("lightweapons_perk_b").requiredLv());
        assertEquals(List.of("lightweapons_perk_root"),
                gen.perks().get("lightweapons_perk_a").requirePerkAll());
        assertEquals(List.of("lightweapons_perk_a"),
                gen.perks().get("lightweapons_perk_b").requirePerkAll());
        assertEquals(List.of("lightweapons_perk_b"),
                gen.perks().get("lightweapons_perk_b_1_1").requirePerkAll());
        assertEquals(0, gen.perks().get("lightweapons_perk_root").requiredLv());
        assertEquals(0, gen.perks().get("lightweapons_perk_root").cost());
    }

    @Test
    @DisplayName("(b) native rewards pass through; TF buffs never enter perk_rewards")
    void nativePassthroughAndBuffExclusion() {
        GeneratedProgression gen = SkillTreeProgressionGenerator.generate(fixture());

        // native passes through verbatim.
        assertEquals(0.1, gen.perks().get("lightweapons_perk_b").perkRewards()
                .get("lightweapons_attackspeedmultiplier_add"));
        assertEquals(0.3, gen.perks().get("lightweapons_perk_b_1_1").perkRewards()
                .get("lightweapons_knockbackmultiplier_add"));

        // A has only TF buffs -> its perk_rewards is empty.
        assertTrue(gen.perks().get("lightweapons_perk_a").perkRewards().isEmpty());

        // No buff key leaks into any perk_rewards, nor into the YAML text.
        List<String> buffKeys = List.of("attack_power", "crit_chance", "crit_damage", "bleed_chance");
        for (GeneratedPerk perk : gen.perks().values()) {
            for (String buffKey : buffKeys) {
                assertFalse(perk.perkRewards().containsKey(buffKey),
                        perk.id() + " perk_rewards must not contain buff key " + buffKey);
            }
        }
        for (String buffKey : buffKeys) {
            assertFalse(gen.yaml().contains(buffKey), "YAML must not mention TF buff key " + buffKey);
        }
    }

    @Test
    @DisplayName("(c) exclusive greek group members lock each other reciprocally")
    void exclusiveWiring() {
        GeneratedProgression gen = SkillTreeProgressionGenerator.generate(fixture());

        assertEquals(List.of("lightweapons_perk_a_beta_1", "lightweapons_perk_a_gamma_1"),
                gen.perks().get("lightweapons_perk_a_alpha_1").perkRewards().get("perks_locked_add"));
        assertEquals(List.of("lightweapons_perk_a_alpha_1", "lightweapons_perk_a_gamma_1"),
                gen.perks().get("lightweapons_perk_a_beta_1").perkRewards().get("perks_locked_add"));
        assertEquals(List.of("lightweapons_perk_a_alpha_1", "lightweapons_perk_a_beta_1"),
                gen.perks().get("lightweapons_perk_a_gamma_1").perkRewards().get("perks_locked_add"));
    }

    @Test
    @DisplayName("(d) names/descriptions are inlined as literals (no <lang.*> refs) and the lang map keeps keys")
    void langIntegrity() {
        GeneratedProgression gen = SkillTreeProgressionGenerator.generate(fixture());

        // The offline lang map still carries a stable key per perk (retained for reference/tests).
        for (GeneratedPerk perk : gen.perks().values()) {
            assertTrue(gen.lang().containsKey(perk.nameKey()), "missing name key " + perk.nameKey());
            assertTrue(gen.lang().containsKey(perk.descriptionKey()),
                    "missing description key " + perk.descriptionKey());
        }

        // TF deploys progression ymls but never merges Valhalla's language file, so display text must be
        // inlined literally — the emitted YAML must contain no <lang.*> reference at all.
        assertFalse(LANG_REF.matcher(gen.yaml()).find(),
                "progression YAML should inline literals, not emit <lang.*> references");

        // Every perk's literal name+description is present verbatim in the emitted YAML.
        for (GeneratedPerk perk : gen.perks().values()) {
            assertTrue(gen.yaml().contains(perk.name()), "missing inlined name for " + perk.id());
            assertTrue(gen.yaml().contains(perk.description()),
                    "missing inlined description for " + perk.id());
        }
    }

    @Test
    @DisplayName("(e) prestige becomes a visible ng1 perk with reset_skill and permanent rewards")
    void prestigeMapping() {
        GeneratedProgression gen = SkillTreeProgressionGenerator.generate(fixture());

        GeneratedPerk ng = gen.perks().get("lightweapons_perk_ng1");
        assertNotNull(ng);
        assertFalse(ng.hidden());
        assertEquals(0, ng.cost());
        assertEquals(100, ng.requiredLv());
        assertFalse(ng.connectionLine().isEmpty(), "prestige should connect from top MAIN");

        Map<String, Object> rewards = ng.perkRewards();
        assertEquals(0, rewards.get("reset_skill_light_weapons"));
        // native prestige reward is passed through with the permanent p: prefix.
        assertEquals(0.1, rewards.get("p:lightweapons_prestigebonus_add"));
        assertEquals(List.of("lightweapons_perk_ng1"), rewards.get("p:perks_permanently_unlocked_add"));
        assertEquals(List.of("lightweapons_perk_ng1"), rewards.get("p:perks_unlocked_remove"));

        // prestige TF buffs are applied by TF, never emitted to Valhalla.
        assertFalse(rewards.containsKey("attack_power"));
        assertFalse(rewards.containsKey("p:attack_power"));

        assertTrue(gen.lang().containsKey("lightweapons_perk_ng1_name"));
        assertEquals("剣聖", gen.lang().get("lightweapons_perk_ng1_name"));
    }

    @Test
    @DisplayName("node ids normalize into safe unique perk ids")
    void idNormalization() {
        GeneratedProgression gen = SkillTreeProgressionGenerator.generate(fixture());

        assertTrue(gen.perks().containsKey("lightweapons_perk_a_alpha_1"));
        assertTrue(gen.perks().containsKey("lightweapons_perk_b_1_1"));
        assertTrue(gen.perks().containsKey("lightweapons_perk_ab"));
    }

    // ---- dedicated-effects (2026-07-23 動的ID方式改修): gate-index-only, never compiled into perk_rewards ----

    @Test
    @DisplayName("dedicated-effects placements never populate perk_rewards, regardless of channel/prefix")
    void dedicatedEffectsNeverLeakIntoPerkRewards() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", new SkillNode("A", "無配線", 10, SkillRole.MAIN, null, null, "STONE", 1, "desc",
                Map.of(), Map.of("arssmithing_sourcecostreduction_add", 2.0), List.of(), List.of(),
                List.of(new DedicatedEffectEntry("glyph:light", null),
                        new DedicatedEffectEntry("recipe:waystone_craft", null),
                        new DedicatedEffectEntry("ritual:animal_summon", null),
                        new DedicatedEffectEntry("feature:vein-mining", null),
                        new DedicatedEffectEntry("ars-tier", 5.0))));
        SkillTree tree = new SkillTree("ARS_SMITHING", "Ars鍛冶", null, "2,10", null, nodes);

        GeneratedProgression gen = SkillTreeProgressionGenerator.generate(tree);

        // Only the hand-authored native entry survives; every dedicated-effect placement is gate-index-only.
        assertEquals(Map.of("arssmithing_sourcecostreduction_add", 2.0),
                gen.perks().get("arssmithing_perk_a").perkRewards());
    }
}
