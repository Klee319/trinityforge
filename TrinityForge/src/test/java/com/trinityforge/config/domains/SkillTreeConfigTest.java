package com.trinityforge.config.domains;

import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillTreeConfig} loads one {@link SkillTree} per {@code skilltree/*.yml}, canonicalizes and
 * allow-lists TF {@code buffs}, and validates node structure fail-soft (malformed nodes/buffs skipped,
 * dangling parent / single-member group warned). Uses a reflective fake {@link Plugin} — the same
 * headless pattern the other domain tests use; the bundled-default copy step no-ops off a jar.
 */
class SkillTreeConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("SkillTreeConfigTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not run headlessly (no plugin jar to copy from)");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static void writeTree(File dataFolder, String fileName, String yaml) throws IOException {
        File file = new File(new File(dataFolder, SkillTreeConfig.DIR), fileName);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
    }

    /** Copies the shipped canonical light_weapons.yml (test classpath) into the temp data folder. */
    private static void copyLightWeapons(File dataFolder) throws IOException {
        File file = new File(new File(dataFolder, SkillTreeConfig.DIR), "light_weapons.yml");
        Files.createDirectories(file.getParentFile().toPath());
        try (InputStream in = SkillTreeConfigTest.class.getClassLoader()
                .getResourceAsStream("skilltree/light_weapons.yml")) {
            assertTrue(in != null, "bundled skilltree/light_weapons.yml must be on the test classpath");
            Files.copy(in, file.toPath());
        }
    }

    private static long countRole(SkillTree tree, SkillRole role) {
        return tree.nodes().values().stream().filter(n -> n.role() == role).count();
    }

    @Test
    void loadsLightWeaponsCanonicalTree(@TempDir File dataFolder) throws IOException {
        copyLightWeapons(dataFolder);
        SkillTreeConfig config = new SkillTreeConfig();

        assertTrue(config.load(fakePlugin(dataFolder)));

        SkillTree tree = config.tree("LIGHT_WEAPONS").orElseThrow();
        assertEquals("LIGHT_WEAPONS", tree.skill());
        assertEquals("軽量武器", tree.displayName());
        assertEquals("DIAMOND_SWORD", tree.icon());
        assertEquals("2,10", tree.startingCoords());

        // 5 main pillars + 15 greek (3 per pillar) + 4 branch = 24 nodes, no intermediates.
        assertEquals(24, tree.nodes().size());
        assertEquals(5, countRole(tree, SkillRole.MAIN));
        assertEquals(15, countRole(tree, SkillRole.GREEK));
        assertEquals(4, countRole(tree, SkillRole.BRANCH));
        assertEquals(0, countRole(tree, SkillRole.INTERMEDIATE));

        // A is the root; B chains off A.
        SkillNode a = tree.node("A").orElseThrow();
        assertEquals(SkillRole.MAIN, a.role());
        assertTrue(a.isRoot());
        assertNull(a.parent());
        assertEquals("A", tree.node("B").orElseThrow().parent());

        // buffs are canonicalized to snake_case and allow-listed.
        // 2026-07-30 の軽量武器ツリー改修で、A は全身加算(buffs)ではなく
        // メインハンド限定(mainhand-multipliers / mainhand-buffs)へ移された。
        assertEquals(1.1, a.mainhandMultipliers().get("layer_1").get("attack_power"), 0.0);
        assertEquals(0.05, a.mainhandBuffs().get("crit_chance"), 0.0);
        // 2026-07-26 職業別草案(戦闘)適用: γ路線は「会心ダメージ」から「出血」へ性格を変えた
        // (α=火力/β=会心・手数 と役割が被っていたため)。crit_damage は載らなくなった。
        SkillNode aGamma = tree.node("A-gamma-1").orElseThrow();
        assertEquals(0.02, aGamma.buffs().get("bleed_chance"), 0.0);
        assertEquals(0.4, aGamma.buffs().get("bleed_damage"), 0.0);
        assertNull(aGamma.buffs().get("crit_damage"));

        // greek exclusivity: all three A-* share the same group.
        assertEquals("A-greek", tree.node("A-alpha-1").orElseThrow().group());
        assertEquals("A-greek", tree.node("A-beta-1").orElseThrow().group());
        assertEquals("A-greek", tree.node("A-gamma-1").orElseThrow().group());

        // 2026-07-26 stat-scope 境界引き直し §2 (C→A 降格): attack_speed は StatVocabulary から外れ、
        // parseBuffs の allow-list (StatVocabulary.isKnown) で drop されるようになった。この perk buff は
        // 実際には 2026-07-25 の attack-speed/attack-speed-bonus 分離仕様の時点で既に
        // PerkAttributeApplier が汎用attrs経路から明示的に除外(no-op)しており、意図した効果は
        // 出ていなかった(既存の死にバフ)。今回の変更はその「無効化されるタイミング」を
        // apply時からparse時へ前倒しし、警告ログで可視化しただけ。
        assertFalse(tree.node("B").orElseThrow().buffs().containsKey("attack_speed"));
        assertFalse(tree.node("B").orElseThrow().native_()
                .containsKey("lightweapons_attackspeedmultiplier_add"));

        Prestige prestige = tree.prestige();
        assertTrue(prestige.enabled());
        assertEquals(100, prestige.atLevel());
        assertEquals(0.15, prestige.buffs().get("attack_power"), 0.0);
        assertEquals(0.1, prestige.buffs().get("crit_chance"), 0.0);
        assertEquals(0.3, prestige.buffs().get("crit_damage"), 0.0);
    }

    @Test
    void treeLookupIsCaseInsensitiveAndImmutable(@TempDir File dataFolder) throws IOException {
        copyLightWeapons(dataFolder);
        SkillTreeConfig config = new SkillTreeConfig();
        assertTrue(config.load(fakePlugin(dataFolder)));

        assertTrue(config.tree("light_weapons").isPresent());
        // all() is a stable immutable snapshot.
        Map<String, SkillTree> all = config.all();
        assertEquals(1, all.size());
        assertSame(all, config.all());
    }

    @Test
    void malformedNodesAndBuffsSkipButTreeStaysHealthy(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "test_tree.yml", """
                skill: TEST_TREE
                display-name: "テスト"
                icon: STONE
                starting-coords: "0,0"
                prestige:
                  enabled: false
                  at-level: 100
                nodes:
                  A:
                    name: "root"
                    level: 10
                    role: main
                    parent: null
                    buffs:
                      attack-power: 0.1
                      made-up-stat: 5.0          # unknown buff key -> dropped
                  BadRole:
                    name: "bad"
                    level: 20
                    role: wizard                 # unknown role -> node skipped
                  Orphan:
                    name: "orphan"
                    level: 30
                    role: branch
                    parent: DOES_NOT_EXIST       # dangling parent -> warn, node kept
                  Lonely:
                    name: "lonely"
                    level: 10
                    role: greek
                    parent: A
                    group: solo-greek            # single-member group -> warn, node kept
                  NoLevel:
                    name: "nolevel"
                    role: main                   # missing level -> node skipped
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        // Warnings fired (dropped buff, skipped nodes, dangling parent, lonely group) -> load false...
        assertFalse(config.load(fakePlugin(dataFolder)));
        // ...but the tree is still built and healthy.
        SkillTree tree = config.tree("TEST_TREE").orElseThrow();

        // Only the three well-formed nodes survive; BadRole and NoLevel are skipped.
        assertEquals(3, tree.nodes().size());
        assertTrue(tree.node("A").isPresent());
        assertTrue(tree.node("Orphan").isPresent());
        assertTrue(tree.node("Lonely").isPresent());
        assertTrue(tree.node("BadRole").isEmpty());
        assertTrue(tree.node("NoLevel").isEmpty());

        // The unknown buff key was dropped; the allowed one kept.
        Map<String, Double> buffs = tree.node("A").orElseThrow().buffs();
        assertEquals(1, buffs.size());
        assertEquals(0.1, buffs.get("attack_power"), 0.0);
        assertFalse(buffs.containsKey("made_up_stat"));
    }

    @Test
    void fileMissingSkillIdIsSkipped(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "broken.yml", """
                display-name: "no skill id"
                nodes:
                  A: { name: "a", level: 10, role: main }
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertFalse(config.load(fakePlugin(dataFolder)));
        assertTrue(config.all().isEmpty());
    }

    @Test
    void nonNumericBuffValueIsDroppedNodeKept(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "numeric.yml", """
                skill: NUM_TREE
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    buffs:
                      attack-power: foo          # non-numeric -> dropped
                      crit-chance: 0.05
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertFalse(config.load(fakePlugin(dataFolder)));
        Map<String, Double> buffs = config.tree("NUM_TREE").orElseThrow().node("A").orElseThrow().buffs();
        assertEquals(1, buffs.size());
        assertEquals(0.05, buffs.get("crit_chance"), 0.0);
        assertFalse(buffs.containsKey("attack_power"));
    }

    @Test
    void parsesNodeAndPrestigeMultipliersAndGatheringBuffs(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "multipliers.yml", """
                skill: MULT_TREE
                prestige:
                  enabled: true
                  at-level: 100
                  buffs:
                    fishing-bonus: 0.5
                  multipliers:
                    gathering:
                      fishing-bonus: 1.2
                  mainhand-multipliers:
                    gathering:
                      fishing-bonus: 1.4
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    buffs:
                      mining-fortune: 0.25
                      fishing-luck: 0.4
                    multipliers:
                      gathering:
                        mining-fortune: 1.1
                        fishing-luck: 1.3
                    mainhand-multipliers:
                      gathering:
                        mining-fortune: 1.25
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertTrue(config.load(fakePlugin(dataFolder)));
        SkillTree tree = config.tree("MULT_TREE").orElseThrow();
        SkillNode node = tree.node("A").orElseThrow();
        assertEquals(0.25, node.buffs().get("mining_fortune"), 0.0);
        assertEquals(0.4, node.buffs().get("fishing_luck"), 0.0);
        assertEquals(1.1, node.multipliers().get("gathering").get("mining_fortune"), 0.0);
        assertEquals(1.3, node.multipliers().get("gathering").get("fishing_luck"), 0.0);
        assertEquals(1.25, node.mainhandMultipliers().get("gathering").get("mining_fortune"), 0.0);
        assertEquals(0.5, tree.prestige().buffs().get("fishing_bonus"), 0.0);
        assertEquals(1.2, tree.prestige().multipliers().get("gathering").get("fishing_bonus"), 0.0);
        assertEquals(1.4, tree.prestige().mainhandMultipliers().get("gathering").get("fishing_bonus"), 0.0);
    }

    @Test
    void syntaxErrorInOneFileSkipsOnlyThatFile(@TempDir File dataFolder) throws IOException {
        copyLightWeapons(dataFolder);
        writeTree(dataFolder, "broken.yml", "skill: BROKEN\nnodes:\n  A: {\n");
        SkillTreeConfig config = new SkillTreeConfig();

        // The broken file is skipped (load false) but the good tree still loads.
        assertFalse(config.load(fakePlugin(dataFolder)));
        assertTrue(config.tree("LIGHT_WEAPONS").isPresent());
        assertTrue(config.tree("BROKEN").isEmpty());
    }

    @Test
    void reloadAbortsWhenPreviousTreeWouldBeLost(@TempDir File dataFolder) throws IOException {
        copyLightWeapons(dataFolder);
        SkillTreeConfig config = new SkillTreeConfig();
        assertTrue(config.load(fakePlugin(dataFolder)));
        assertTrue(config.tree("LIGHT_WEAPONS").isPresent());

        // Replace the only good file with a syntax-broken file of a different skill id so the
        // candidate drops LIGHT_WEAPONS — atomic policy must keep the previous snapshot.
        File treeFile = new File(new File(dataFolder, SkillTreeConfig.DIR), "light_weapons.yml");
        Files.writeString(treeFile.toPath(), "skill: OTHER\nnodes:\n  A: {\n");
        assertFalse(config.load(fakePlugin(dataFolder)));
        assertTrue(config.tree("LIGHT_WEAPONS").isPresent(),
                "previous LIGHT_WEAPONS tree must be retained on aborted reload");
    }

    @Test
    void emptyDirectoryLoadsCleanly(@TempDir File dataFolder) throws IOException {
        Files.createDirectories(new File(dataFolder, SkillTreeConfig.DIR).toPath());
        SkillTreeConfig config = new SkillTreeConfig();

        assertTrue(config.load(fakePlugin(dataFolder)));
        assertTrue(config.all().isEmpty());
    }

    @Test
    void unrecognizedDedicatedEffectIdIsDroppedNodeKept(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "dedi.yml", """
                skill: DEDI_TREE
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    dedicated-effects:
                      - id: not-a-real-effect
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertFalse(config.load(fakePlugin(dataFolder)));
        SkillNode a = config.tree("DEDI_TREE").orElseThrow().node("A").orElseThrow();
        assertTrue(a.dedicatedEffects().isEmpty());
    }

    @Test
    void unknownFeatureVocabWordIsDroppedNodeKept(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "dedi_feat.yml", """
                skill: DEDI_FEAT
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    dedicated-effects:
                      - id: feature:not-a-real-feature
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertFalse(config.load(fakePlugin(dataFolder)));
        SkillNode a = config.tree("DEDI_FEAT").orElseThrow().node("A").orElseThrow();
        assertTrue(a.dedicatedEffects().isEmpty());
    }

    @Test
    void uniqueDedicatedEffectPlacedOnTwoNodesAcrossTreesWarnsButBothTreesLoad(
            @TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "dedi_a.yml", """
                skill: DEDI_A
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    dedicated-effects:
                      - id: glyph:light
                """);
        writeTree(dataFolder, "dedi_b.yml", """
                skill: DEDI_B
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    dedicated-effects:
                      - id: glyph:light
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        // Duplicate unique placement across trees -> load() reports issues...
        assertFalse(config.load(fakePlugin(dataFolder)));
        // ...but both trees still load in full, nothing is dropped.
        assertTrue(config.tree("DEDI_A").orElseThrow().node("A").orElseThrow()
                .dedicatedEffects().stream().anyMatch(e -> e.id().equals("glyph:light")));
        assertTrue(config.tree("DEDI_B").orElseThrow().node("A").orElseThrow()
                .dedicatedEffects().stream().anyMatch(e -> e.id().equals("glyph:light")));
    }

    @Test
    void arsTierPlacedOnTwoNodesDoesNotWarnBecauseItIsAdditive(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "dedi_ars.yml", """
                skill: DEDI_ARS
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    dedicated-effects:
                      - id: ars-tier
                        value: 1
                  B:
                    name: "b"
                    level: 20
                    role: main
                    dedicated-effects:
                      - id: ars-tier
                        value: 1
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertTrue(config.load(fakePlugin(dataFolder)));
    }

    @Test
    void arsTierRequiringValueWithoutOneIsDropped(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "dedi_val.yml", """
                skill: DEDI_VAL
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    dedicated-effects:
                      - id: ars-tier
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertFalse(config.load(fakePlugin(dataFolder)));
        assertTrue(config.tree("DEDI_VAL").orElseThrow().node("A").orElseThrow().dedicatedEffects().isEmpty());
    }

    @Test
    void dismantleUnlockFeatureRequiringValueWithoutOneIsDropped(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "dedi_dismantle.yml", """
                skill: DEDI_DISMANTLE
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    dedicated-effects:
                      - id: feature:dismantle-unlock
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertFalse(config.load(fakePlugin(dataFolder)));
        assertTrue(config.tree("DEDI_DISMANTLE").orElseThrow().node("A").orElseThrow()
                .dedicatedEffects().isEmpty());
    }

    @Test
    @DisplayName("2026-07-27 農業「ゴミ食」段階化: feature:junkfood-inversion is LEVEL(%), so a placement "
            + "without 'value' is dropped at parse-time with a warning (load() returns false) — there is no "
            + "SCALE-style 'defaults to 100%' fallback; the build itself enforces every placement carries a "
            + "value (see AllSkillTreesLoadTest, which fails the whole suite on this exact warning)")
    void junkfoodInversionFeatureRequiringValueWithoutOneIsDropped(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "dedi_junkfood.yml", """
                skill: DEDI_JUNKFOOD
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    dedicated-effects:
                      - id: feature:junkfood-inversion
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertFalse(config.load(fakePlugin(dataFolder)));
        assertTrue(config.tree("DEDI_JUNKFOOD").orElseThrow().node("A").orElseThrow()
                .dedicatedEffects().isEmpty());
    }

    @Test
    @DisplayName("2026-07-27 農業「ゴミ食」段階化: an explicit 'value' on feature:junkfood-inversion is kept "
            + "verbatim (A-alpha-1:100 / A-alpha-2:150 style placements parse cleanly)")
    void junkfoodInversionFeatureWithExplicitValueParsesCleanly(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "dedi_junkfood_ok.yml", """
                skill: DEDI_JUNKFOOD_OK
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    dedicated-effects:
                      - id: feature:junkfood-inversion
                        value: 100
                  B:
                    name: "b"
                    level: 20
                    role: branch
                    parent: A
                    dedicated-effects:
                      - id: feature:junkfood-inversion
                        value: 150
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertTrue(config.load(fakePlugin(dataFolder)));
        assertEquals(100.0, config.tree("DEDI_JUNKFOOD_OK").orElseThrow().node("A").orElseThrow()
                .dedicatedEffects().get(0).value());
        assertEquals(150.0, config.tree("DEDI_JUNKFOOD_OK").orElseThrow().node("B").orElseThrow()
                .dedicatedEffects().get(0).value());
    }

    @Test
    @DisplayName("2026-07-25 gather-rework-active-framework §1/§5: a SCALE feature placement (e.g. "
            + "feature:vein-mining) with no 'value' is kept and defaults to tier 1, not dropped like LEVEL")
    void scaleFeatureWithoutValueDefaultsToTierOneInsteadOfBeingDropped(@TempDir File dataFolder)
            throws IOException {
        writeTree(dataFolder, "dedi_scale.yml", """
                skill: DEDI_SCALE
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    dedicated-effects:
                      - id: feature:vein-mining
                  B:
                    name: "b"
                    level: 20
                    role: branch
                    parent: A
                    dedicated-effects:
                      - id: feature:vein-mining
                        value: 3
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertTrue(config.load(fakePlugin(dataFolder)));
        var noValuePlacement = config.tree("DEDI_SCALE").orElseThrow().node("A").orElseThrow()
                .dedicatedEffects().get(0);
        assertEquals("feature:vein-mining", noValuePlacement.id());
        assertEquals(1.0, noValuePlacement.value());

        var explicitPlacement = config.tree("DEDI_SCALE").orElseThrow().node("B").orElseThrow()
                .dedicatedEffects().get(0);
        assertEquals(3.0, explicitPlacement.value());
    }

    @Test
    @DisplayName("2026-07-23 verifier指摘②: RATE_KEYS buffs are percent-normalized to [0,1]; FLAT/INTEGER buffs "
            + "(mana_bonus, lapis_cost_reduction) are left untouched")
    void buffsAreNormalizedThroughPercentStatNormalize(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "normalize.yml", """
                skill: NORMALIZE_TREE
                nodes:
                  A:
                    name: "a"
                    level: 10
                    role: main
                    buffs:
                      material-refund-chance: 15   # RATE_KEYS member: percent-points authoring -> 0.15
                      mana-bonus: 50                # FLAT, never coerced regardless of RATE_KEYS
                      lapis-cost-reduction: 50       # 2026-07-23仕様確定: 個数(FLAT), RATE_KEYSから除外済み
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertTrue(config.load(fakePlugin(dataFolder)));
        Map<String, Double> buffs = config.tree("NORMALIZE_TREE").orElseThrow().node("A").orElseThrow().buffs();
        assertEquals(0.15, buffs.get("material_refund_chance"), 0.0);
        assertEquals(50.0, buffs.get("mana_bonus"), 0.0);
        assertEquals(50.0, buffs.get("lapis_cost_reduction"), 0.0);
    }

    @Test
    void reloadSwapsSnapshotAtomically(@TempDir File dataFolder) throws IOException {
        copyLightWeapons(dataFolder);
        SkillTreeConfig config = new SkillTreeConfig();
        assertTrue(config.load(fakePlugin(dataFolder)));
        assertEquals(24, config.tree("LIGHT_WEAPONS").orElseThrow().nodes().size());

        // Add a second tree and reload: both are present afterwards.
        writeTree(dataFolder, "mini.yml", """
                skill: MINI
                nodes:
                  A: { name: "a", level: 10, role: main }
                """);
        assertTrue(config.load(fakePlugin(dataFolder)));
        assertEquals(2, config.all().size());
        assertTrue(config.tree("MINI").isPresent());
        assertTrue(config.tree("LIGHT_WEAPONS").isPresent());
    }

    // --- set-buffs (armor-set-buffs migration §1) ---

    @Test
    void setBuffsTiersThreeAndFourAreParsedOnLightArmorTree(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "light_armor.yml", """
                skill: LIGHT_ARMOR
                nodes:
                  C:
                    name: "c"
                    level: 50
                    role: main
                    set-buffs:
                      3: { dodge-chance: 0.1 }
                      4: { dodge-chance: 0.15 }
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertTrue(config.load(fakePlugin(dataFolder)));
        SkillNode c = config.tree("LIGHT_ARMOR").orElseThrow().node("C").orElseThrow();
        assertEquals(0.1, c.setBuffs().get(3).get("dodge_chance"), 0.0);
        assertEquals(0.15, c.setBuffs().get(4).get("dodge_chance"), 0.0);
    }

    @Test
    void setBuffsTiersOtherThanThreeOrFourAreDroppedWithWarning(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "heavy_armor.yml", """
                skill: HEAVY_ARMOR
                nodes:
                  C:
                    name: "c"
                    level: 50
                    role: main
                    set-buffs:
                      1: { dodge-chance: 0.1 }
                      2: { dodge-chance: 0.1 }
                      5: { dodge-chance: 0.1 }
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertFalse(config.load(fakePlugin(dataFolder)));
        SkillNode c = config.tree("HEAVY_ARMOR").orElseThrow().node("C").orElseThrow();
        assertTrue(c.setBuffs().isEmpty(), "1/2/5 は不正な段なので全て無視されるべき");
    }

    @Test
    void setBuffsOnNonArmorTreeIsIgnoredWithWarningNodeKept(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "not_armor.yml", """
                skill: LIGHT_WEAPONS
                nodes:
                  C:
                    name: "c"
                    level: 50
                    role: main
                    set-buffs:
                      3: { dodge-chance: 0.1 }
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertFalse(config.load(fakePlugin(dataFolder)));
        SkillNode c = config.tree("LIGHT_WEAPONS").orElseThrow().node("C").orElseThrow();
        assertTrue(c.setBuffs().isEmpty(), "light_armor/heavy_armor以外のツリーではset-buffsは無視されるべき");
        assertEquals("c", c.name(), "ノード自体は維持されるべき");
    }

    @Test
    void setBuffsOnPrestigeIsParsedOnHeavyArmorTree(@TempDir File dataFolder) throws IOException {
        writeTree(dataFolder, "heavy_armor_prestige.yml", """
                skill: HEAVY_ARMOR
                prestige:
                  enabled: true
                  at-level: 100
                  set-buffs:
                    3: { knockback-resistance: 0.1 }
                    4: { knockback-resistance: 0.2 }
                nodes:
                  A: { name: "a", level: 10, role: main }
                """);
        SkillTreeConfig config = new SkillTreeConfig();

        assertTrue(config.load(fakePlugin(dataFolder)));
        Prestige prestige = config.tree("HEAVY_ARMOR").orElseThrow().prestige();
        assertEquals(0.1, prestige.setBuffs().get(3).get("knockback_resistance"), 0.0);
        assertEquals(0.2, prestige.setBuffs().get(4).get("knockback_resistance"), 0.0);
    }
}
