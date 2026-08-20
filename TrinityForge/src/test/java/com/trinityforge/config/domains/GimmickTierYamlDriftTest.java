package com.trinityforge.config.domains;

import com.trinityforge.skilltree.DedicatedEffectEntry;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillTree;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ドリフト検知(2026-07-28 数値のギミックyml集約): {@code skilltree/smithing.yml} /
 * {@code skilltree/digging.yml} 側に置かれた {@code feature:furnace-smelt-*} /
 * {@code feature:digging-durability-*} の {@code value}(tier番号)が、対応する
 * {@code stats/smithing-gimmick.yml} / {@code stats/digging-gimmick.yml} 側の tiers テーブルに
 * 実在するキーであることを固定する。
 *
 * <p><b>なぜ必要か</b> — この2ファイルの組は「スキルツリー側はtier番号だけを持ち、実際の%は
 * ギミックymlのtiersテーブルが持つ」という設計(SmithingGimmickConfig/DiggingGimmickConfig の
 * warn-onceロジックを参照)。tierの完全一致が取れない場合は「tier以下で最大の行」へフロア解決される
 * ため、片方だけ編集してもテストもビルドも壊れず、意図しない%へ無言ですり替わる。この
 * テストはその無言のずれを固定する({@link RecipeRitualGateChannelDriftTest} と同じ「実クラスパスの
 * 本物のconfigをコピーして本物のローダーで読む」流儀)。
 */
class GimmickTierYamlDriftTest {

    private static Plugin fakePlugin(File dataFolder, Logger logger) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> logger;
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    /** Copies one bundled {@code skilltree/<file>} resource into {@code dataFolder} and loads it. */
    private static SkillTree loadSkillTree(File dataFolder, String fileName, String skillId) throws IOException {
        File dir = new File(dataFolder, SkillTreeConfig.DIR);
        Files.createDirectories(dir.toPath());
        try (InputStream in = GimmickTierYamlDriftTest.class.getClassLoader()
                .getResourceAsStream(SkillTreeConfig.DIR + "/" + fileName)) {
            assertTrue(in != null, "bundled skilltree/" + fileName + " must be on the test classpath");
            Files.copy(in, new File(dir, fileName).toPath());
        }
        SkillTreeConfig config = new SkillTreeConfig();
        Logger logger = Logger.getLogger("GimmickTierYamlDriftTest-" + System.nanoTime());
        assertTrue(config.load(fakePlugin(dataFolder, logger)), fileName + " must load OK");
        return config.tree(skillId).orElseThrow(
                () -> new AssertionError(fileName + " must declare skill: " + skillId));
    }

    /** Every {@code value} placed on any node under the dynamic gate id {@code "feature:" + featureId}. */
    private static List<Integer> tierValuesFor(SkillTree tree, String featureId) {
        String gateId = "feature:" + featureId;
        List<Integer> values = new ArrayList<>();
        for (SkillNode node : tree.nodes().values()) {
            for (DedicatedEffectEntry entry : node.dedicatedEffects()) {
                if (gateId.equals(entry.id())) {
                    assertTrue(entry.value() != null,
                            "node " + node.id() + " places " + gateId + " without a value (tier番号必須)");
                    values.add((int) Math.round(entry.value()));
                }
            }
        }
        return values;
    }

    /** Loads the real (classpath) {@code path} as a YamlConfiguration, same fixture style as gimmick config tests. */
    private static YamlConfiguration loadRealYaml(File tempDir, String path) throws IOException {
        File file = new File(tempDir, path);
        Files.createDirectories(file.getParentFile().toPath());
        try (InputStream in = GimmickTierYamlDriftTest.class.getClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "bundled " + path + " must be on the test classpath");
            Files.copy(in, file.toPath());
        }
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new IOException(ex);
        }
        return yaml;
    }

    /** Integer tier keys declared under {@code section.tiers} (e.g. {@code furnace-smelt.speed.tiers}). */
    private static Set<Integer> declaredTierKeys(YamlConfiguration yaml, String tiersPath) {
        ConfigurationSection tiers = yaml.getConfigurationSection(tiersPath);
        Set<Integer> keys = new TreeSet<>();
        if (tiers == null) {
            return keys;
        }
        for (String key : tiers.getKeys(false)) {
            try {
                keys.add(Integer.parseInt(key.trim()));
            } catch (NumberFormatException ignored) {
                // non-numeric tier keys are a separate malformed-row concern, not this drift check's job.
            }
        }
        return keys;
    }

    @Test
    @DisplayName("smithing.yml A-1/A-2/A-3(furnace-smelt-speed)のtier番号は smithing-gimmick.yml の speed.tiers に実在する")
    void furnaceSmeltSpeedTiersExistInGimmickYaml(@TempDir File tempDir) throws IOException {
        SkillTree tree = loadSkillTree(tempDir, "smithing.yml", "SMITHING");
        List<Integer> placedTiers = tierValuesFor(tree, "furnace-smelt-speed");
        assertTrue(!placedTiers.isEmpty(), "test setup sanity: smithing.yml must place furnace-smelt-speed somewhere");

        YamlConfiguration gimmick = loadRealYaml(tempDir, SmithingGimmickConfig.PATH);
        Set<Integer> declared = declaredTierKeys(gimmick, "furnace-smelt.speed.tiers");

        for (Integer tier : placedTiers) {
            assertTrue(declared.contains(tier),
                    "smithing.yml places feature:furnace-smelt-speed tier=" + tier
                            + " but stats/smithing-gimmick.yml furnace-smelt.speed.tiers has no exact tier "
                            + tier + " (declared: " + declared + "). Without an exact match this floors to a "
                            + "lower tier's % (or WARNs+falls back) at runtime — update one file or the other.");
        }
    }

    @Test
    @DisplayName("smithing.yml B-1/B-2/B-3(furnace-smelt-bonus)のtier番号は smithing-gimmick.yml の bonus.tiers に実在する")
    void furnaceSmeltBonusTiersExistInGimmickYaml(@TempDir File tempDir) throws IOException {
        SkillTree tree = loadSkillTree(tempDir, "smithing.yml", "SMITHING");
        List<Integer> placedTiers = tierValuesFor(tree, "furnace-smelt-bonus");
        assertTrue(!placedTiers.isEmpty(), "test setup sanity: smithing.yml must place furnace-smelt-bonus somewhere");

        YamlConfiguration gimmick = loadRealYaml(tempDir, SmithingGimmickConfig.PATH);
        Set<Integer> declared = declaredTierKeys(gimmick, "furnace-smelt.bonus.tiers");

        for (Integer tier : placedTiers) {
            assertTrue(declared.contains(tier),
                    "smithing.yml places feature:furnace-smelt-bonus tier=" + tier
                            + " but stats/smithing-gimmick.yml furnace-smelt.bonus.tiers has no exact tier "
                            + tier + " (declared: " + declared + ").");
        }
    }

    @Test
    @DisplayName("digging.yml C-1(digging-durability-vanilla-exp)のtier番号は digging-gimmick.yml の vanilla-exp.tiers に実在する")
    void diggingVanillaExpTiersExistInGimmickYaml(@TempDir File tempDir) throws IOException {
        SkillTree tree = loadSkillTree(tempDir, "digging.yml", "DIGGING");
        List<Integer> placedTiers = tierValuesFor(tree, "digging-durability-vanilla-exp");
        assertTrue(!placedTiers.isEmpty(), "test setup sanity: digging.yml must place digging-durability-vanilla-exp somewhere");

        YamlConfiguration gimmick = loadRealYaml(tempDir, DiggingGimmickConfig.PATH);
        Set<Integer> declared = declaredTierKeys(gimmick, "durability-exp.vanilla-exp.tiers");

        for (Integer tier : placedTiers) {
            assertTrue(declared.contains(tier),
                    "digging.yml places feature:digging-durability-vanilla-exp tier=" + tier
                            + " but stats/digging-gimmick.yml durability-exp.vanilla-exp.tiers has no exact tier "
                            + tier + " (declared: " + declared + ").");
        }
    }

    @Test
    @DisplayName("digging.yml C-2(digging-durability-job-exp)のtier番号は digging-gimmick.yml の job-exp.tiers に実在する")
    void diggingJobExpTiersExistInGimmickYaml(@TempDir File tempDir) throws IOException {
        SkillTree tree = loadSkillTree(tempDir, "digging.yml", "DIGGING");
        List<Integer> placedTiers = tierValuesFor(tree, "digging-durability-job-exp");
        assertTrue(!placedTiers.isEmpty(), "test setup sanity: digging.yml must place digging-durability-job-exp somewhere");

        YamlConfiguration gimmick = loadRealYaml(tempDir, DiggingGimmickConfig.PATH);
        Set<Integer> declared = declaredTierKeys(gimmick, "durability-exp.job-exp.tiers");

        for (Integer tier : placedTiers) {
            assertTrue(declared.contains(tier),
                    "digging.yml places feature:digging-durability-job-exp tier=" + tier
                            + " but stats/digging-gimmick.yml durability-exp.job-exp.tiers has no exact tier "
                            + tier + " (declared: " + declared + ").");
        }
    }
}
