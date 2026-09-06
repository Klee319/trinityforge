package com.trinityforge.skilltree.runtime;

import com.trinityforge.config.domains.SkillTreeConfig;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.PerkNaming;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.trinityforge.stats.StatVocabulary;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-tree runtime smoke for {@link PerkBuffResolver} over <b>all 16</b> shipped trees (SKILL_TREE design
 * section 3.2). Confirms the pure {@link PerkBuffResolver#compute} aggregation stays exception-free and
 * well-formed when fed the real canonical config: unlocking every node (and every prestige) across all
 * trees yields only finite values keyed exclusively by the design section B allow-list, split correctly
 * attacker vs defender. An empty unlocked set degrades to {@link PerkBuffs#EMPTY}.
 */
class AllSkillTreesPerkBuffTest {

    private static final List<String> FILES = List.of(
            "light_weapons.yml", "heavy_weapons.yml", "archery.yml", "light_armor.yml", "heavy_armor.yml",
            "ars_magic.yml", "mining.yml", "woodcutting.yml", "farming.yml", "enchanting.yml",
            "digging.yml", "smithing.yml", "alchemy.yml", "fishing.yml", "ars_smithing.yml", "power.yml");

    private static final Set<String> ATTACK_KEYS = Set.copyOf(
            StatVocabulary.allKeys().stream()
                    .filter(StatVocabulary::isAttack)
                    .collect(Collectors.toSet()));

    private static final Set<String> DEFENSE_KEYS = Set.copyOf(
            StatVocabulary.allKeys().stream()
                    .filter(StatVocabulary::isDefense)
                    .collect(Collectors.toSet()));

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("AllSkillTreesPerkBuffTest");
            case "saveResource" -> throw new AssertionError("saveResource() must not run headlessly");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static Collection<SkillTree> loadAll(File dataFolder) throws IOException {
        File dir = new File(dataFolder, SkillTreeConfig.DIR);
        Files.createDirectories(dir.toPath());
        for (String fileName : FILES) {
            try (InputStream in = AllSkillTreesPerkBuffTest.class.getClassLoader()
                    .getResourceAsStream(SkillTreeConfig.DIR + "/" + fileName)) {
                assertTrue(in != null, "bundled skilltree/" + fileName + " must be on the test classpath");
                Files.copy(in, new File(dir, fileName).toPath());
            }
        }
        SkillTreeConfig config = new SkillTreeConfig();
        config.load(fakePlugin(dataFolder));
        return config.all().values();
    }

    /** Every node perk id plus every prestige perk id, across every loaded tree. */
    private static Set<String> unlockEverything(Collection<SkillTree> trees) {
        Set<String> unlocked = new HashSet<>();
        for (SkillTree tree : trees) {
            for (SkillNode node : tree.nodes().values()) {
                unlocked.add(PerkNaming.perkId(tree.skill(), node.id()));
            }
            if (tree.prestige() != null && tree.prestige().enabled()) {
                unlocked.add(PerkNaming.prestigePerkId(tree.skill(), 1));
            }
        }
        return unlocked;
    }

    @Test
    @DisplayName("unlocking every perk across all 16 trees aggregates finite, allow-listed, side-correct buffs")
    void unlockAll_aggregatesCleanly(@TempDir File dataFolder) throws IOException {
        Collection<SkillTree> trees = loadAll(dataFolder);

        PerkBuffs buffs = PerkBuffResolver.compute(unlockEverything(trees), trees);

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Double> entry : buffs.attack().entrySet()) {
            if (!ATTACK_KEYS.contains(entry.getKey())) {
                problems.add("attack map has non-attack key '" + entry.getKey() + "'");
            }
            if (entry.getValue() == null || !Double.isFinite(entry.getValue())) {
                problems.add("attack key '" + entry.getKey() + "' has non-finite value " + entry.getValue());
            }
        }
        for (Map.Entry<String, Double> entry : buffs.defense().entrySet()) {
            if (!DEFENSE_KEYS.contains(entry.getKey())) {
                problems.add("defense map has non-defense key '" + entry.getKey() + "'");
            }
            if (entry.getValue() == null || !Double.isFinite(entry.getValue())) {
                problems.add("defense key '" + entry.getKey() + "' has non-finite value " + entry.getValue());
            }
        }
        assertTrue(problems.isEmpty(),
                "cross-tree aggregation violations:\n  " + String.join("\n  ", problems));
    }

    /**
     * 2026-08-05 実サーバ報告「釣りボーナスは%では？ドロップ増加ステと同じ期待値仕様だったはず」の回帰ガード。
     *
     * <p>出荷 {@code fishing.yml} は他の%系と同じくパーセントポイントで書かれている
     * (A:5 / C:10 / prestige:10)。{@code PercentStatNormalize.RATE_KEYS} から fishing-bonus の登録を
     * 外すと、この合算が 25.0 のまま {@code GatheringPolicy.expectedExtra} へ渡り
     * <b>1回の釣りで追加ドロップ25個</b>になる。期待個数は1未満に収まっていなければならない。
     */
    @Test
    @DisplayName("出荷スキルツリーの釣りボーナス合算は期待値0.25個(%として矯正されている)")
    void shippedFishingBonusIsARateNotARawCount(@TempDir File dataFolder) throws IOException {
        Collection<SkillTree> trees = loadAll(dataFolder);

        PerkBuffs buffs = PerkBuffResolver.compute(unlockEverything(trees), trees);

        Double fishingBonus = buffs.general().get("fishing_bonus");
        assertTrue(fishingBonus != null, "fishing_bonus が general バフに出ていない(出荷ツリーの付与が消えた)");
        assertTrue(Math.abs(fishingBonus - 0.25) < 1e-9,
                "A:5% + C:10% + prestige:10% = 0.25 のはずだが " + fishingBonus
                        + " ── パーセント矯正が効いていないと 25.0(=追加ドロップ25個)になる");
    }

    @Test
    @DisplayName("鍛冶とArs鍛冶のプレステージ品質基準は作業台と儀式に分離されている")
    void prestigeQualityBonuses_areSeparatedBetweenWorkbenchAndRitual(@TempDir File dataFolder) throws IOException {
        Collection<SkillTree> trees = loadAll(dataFolder);

        PerkBuffs smithing = PerkBuffResolver.compute(
                Set.of(PerkNaming.prestigePerkId("SMITHING", 1)), trees);
        PerkBuffs arsSmithing = PerkBuffResolver.compute(
                Set.of(PerkNaming.prestigePerkId("ARS_SMITHING", 1)), trees);

        assertEquals(2.0, smithing.general().get("workbench_quality_bonus"), 1e-9,
                "鍛冶プレステージは作業台品質基準を+2する");
        assertFalse(smithing.general().containsKey("ritual_quality_bonus"),
                "鍛冶プレステージは儀式品質基準を変えない");
        assertEquals(2.0, arsSmithing.general().get("ritual_quality_bonus"), 1e-9,
                "Ars鍛冶プレステージは儀式品質基準を+2する");
        assertFalse(arsSmithing.general().containsKey("workbench_quality_bonus"),
                "Ars鍛冶プレステージは作業台品質基準を変えない");
    }

    /** Shipped skill trees must not retain legacy dedicated-effect ids. */
    private static final Set<String> ALLOWED_UNCONVERTED_DEDICATED_EFFECTS = Set.of();

    private static final java.util.regex.Pattern UNCONVERTED_DEDICATED_EFFECT_WARNING = java.util.regex.Pattern
            .compile("^\\[skilltree/([^]]+)] node '([^']+)' dedicated-effect '([^']+)' has an unrecognized"
                    + " prefix or is a pre-conversion legacy id; skipped$");

    /**
     * config.load(...) の警告を収集し、旧形式IDが再混入した場合を含む設定不整合を検出する。
     */
    @Test
    @DisplayName("load() emits no skill-tree configuration warnings")
    void loadWarnings_matchIntentionalStopAllowListExactly(@TempDir File dataFolder) throws IOException {
        File dir = new File(dataFolder, SkillTreeConfig.DIR);
        Files.createDirectories(dir.toPath());
        for (String fileName : FILES) {
            try (InputStream in = AllSkillTreesPerkBuffTest.class.getClassLoader()
                    .getResourceAsStream(SkillTreeConfig.DIR + "/" + fileName)) {
                assertTrue(in != null, "bundled skilltree/" + fileName + " must be on the test classpath");
                Files.copy(in, new File(dir, fileName).toPath());
            }
        }

        Logger logger = Logger.getLogger("AllSkillTreesPerkBuffTest.guard." + System.nanoTime());
        List<String> warnings = new ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);

        InvocationHandler ih = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> logger;
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, ih);

        SkillTreeConfig config = new SkillTreeConfig();
        config.load(plugin);

        Set<String> unexpected = new HashSet<>();
        Set<String> seenAllowed = new HashSet<>();
        for (String message : warnings) {
            java.util.regex.Matcher matcher = UNCONVERTED_DEDICATED_EFFECT_WARNING.matcher(message);
            if (matcher.matches()) {
                String key = matcher.group(1) + "|" + matcher.group(2) + "|" + matcher.group(3);
                if (ALLOWED_UNCONVERTED_DEDICATED_EFFECTS.contains(key)) {
                    seenAllowed.add(key);
                } else {
                    unexpected.add(message);
                }
                continue;
            }
            unexpected.add(message);
        }

        assertTrue(unexpected.isEmpty(),
                "unexpected skill-tree load warning(s) (possible yml typo/regression):\n  "
                        + String.join("\n  ", unexpected));
        assertTrue(seenAllowed.equals(ALLOWED_UNCONVERTED_DEDICATED_EFFECTS),
                "legacy dedicated-effect warning(s) did not match the expected empty set:\n  expected-but-missing="
                        + diff(ALLOWED_UNCONVERTED_DEDICATED_EFFECTS, seenAllowed));
    }

    private static Set<String> diff(Set<String> expected, Set<String> actual) {
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }

    @Test
    @DisplayName("an empty unlocked set degrades to PerkBuffs.EMPTY over the real trees")
    void emptyUnlocked_isEmpty(@TempDir File dataFolder) throws IOException {
        Collection<SkillTree> trees = loadAll(dataFolder);

        PerkBuffs buffs = PerkBuffResolver.compute(Set.of(), trees);

        assertTrue(buffs.attack().isEmpty(), "no unlocked perks -> empty attack map");
        assertTrue(buffs.defense().isEmpty(), "no unlocked perks -> empty defense map");
    }
}
