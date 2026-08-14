package com.trinityforge.config.domains;

import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillTree;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the SHIPPED {@code skilltree/enchanting.yml} and {@code skilltree/alchemy.yml} through the real
 * {@link SkillTreeConfig} parser and asserts the new {@code enchant_luck}/{@code enchanting_exp_bonus}/
 * {@code potion_quality_bonus}/{@code brew_speed_bonus} buffs on the target nodes parsed cleanly (not
 * silently dropped as an unknown key — see {@link SkillTreeConfig}'s {@code parseBuffs}, which drops and
 * warns on any key {@link com.trinityforge.stats.StatVocabulary#isKnown} rejects).
 */
class EnchantingAlchemyBuffsWiringTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("EnchantingAlchemyBuffsWiringTest");
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

    private static void copyBundled(File dataFolder, String fileName) throws IOException {
        File file = new File(new File(dataFolder, SkillTreeConfig.DIR), fileName);
        Files.createDirectories(file.getParentFile().toPath());
        try (InputStream in = EnchantingAlchemyBuffsWiringTest.class.getClassLoader()
                .getResourceAsStream("skilltree/" + fileName)) {
            assertTrue(in != null, "bundled skilltree/" + fileName + " must be on the test classpath");
            Files.copy(in, file.toPath());
        }
    }

    @Test
    void enchantingTreeGrantsEnchantLuckAndExpBonusOnTheSixTargetNodes(@TempDir File dataFolder) throws IOException {
        copyBundled(dataFolder, "enchanting.yml");
        SkillTreeConfig config = new SkillTreeConfig();
        assertTrue(config.load(fakePlugin(dataFolder)), "enchanting.yml must load with no validation issues");

        SkillTree tree = config.all().get("ENCHANTING");
        assertTrue(tree != null, "ENCHANTING tree must be loaded");

        // 2026-07-26 職業別草案(生産)適用: enchant_luck の総量を大幅に圧縮し(α路線フルで約31)、
        // EXP増減のトレードオフは「ギリシャ路線でどちらかを選ぶ」性格付けに一本化した。
        // 主軸 A/C は運だけを配り、EXP 増減は載せない(下の assertMainAxisLuckOnly が担保)。
        assertMainAxisLuckOnly(tree, "A", 5.0);
        assertMainAxisLuckOnly(tree, "C", 10.0);
        assertNodeBuffs(tree, "A-alpha-1", 8.0, -0.05);
        assertNodeBuffs(tree, "A-alpha-2", 8.0, -0.05);
        assertNodeBuffs(tree, "A-beta-1", 4.0, 0.05);
        assertNodeBuffs(tree, "A-beta-2", 4.0, 0.05);
    }

    private static void assertNodeBuffs(SkillTree tree, String nodeId, double expectedLuck, double expectedExpBonus) {
        SkillNode node = tree.nodes().get(nodeId);
        assertTrue(node != null, "node " + nodeId + " must exist");
        assertEquals(expectedLuck, node.buffs().get("enchant_luck"), "node " + nodeId + " enchant_luck");
        assertEquals(expectedExpBonus, node.buffs().get("enchanting_exp_bonus"), "node " + nodeId + " enchanting_exp_bonus");
    }

    /** 主軸ノード: enchant_luck だけを配り、EXP 増減のトレードオフは載せない。 */
    private static void assertMainAxisLuckOnly(SkillTree tree, String nodeId, double expectedLuck) {
        SkillNode node = tree.nodes().get(nodeId);
        assertTrue(node != null, "node " + nodeId + " must exist");
        assertEquals(expectedLuck, node.buffs().get("enchant_luck"), "node " + nodeId + " enchant_luck");
        assertTrue(node.buffs().get("enchanting_exp_bonus") == null,
                "主軸ノード " + nodeId + " に enchanting_exp_bonus を載せない(EXP増減はギリシャ路線の選択要素)");
    }

    @Test
    void alchemyTreeGrantsPotionQualityAndBrewSpeedBuffs(@TempDir File dataFolder) throws IOException {
        copyBundled(dataFolder, "alchemy.yml");
        SkillTreeConfig config = new SkillTreeConfig();
        assertTrue(config.load(fakePlugin(dataFolder)), "alchemy.yml must load with no validation issues");

        SkillTree tree = config.all().get("ALCHEMY");
        assertTrue(tree != null, "ALCHEMY tree must be loaded");

        assertEquals(1.0, tree.nodes().get("A").buffs().get("potion_quality_bonus"));
        assertEquals(0.05, tree.nodes().get("A").buffs().get("brew_speed_bonus"));
        assertEquals(1.0, tree.nodes().get("B").buffs().get("potion_quality_bonus"));
        // 2026-07-26 職業別草案(生産)適用: 到達点 E と各路線の2段目を引き上げ、
        // 1路線フルで potion_quality_bonus 合計 +8〜+9 に収まるよう配分し直した。
        assertEquals(3.0, tree.nodes().get("E").buffs().get("potion_quality_bonus"));
        assertEquals(2.0, tree.nodes().get("B-alpha-2").buffs().get("potion_quality_bonus"));
        assertEquals(2.0, tree.nodes().get("B-beta-2").buffs().get("potion_quality_bonus"));
        assertEquals(1.0, tree.nodes().get("B-beta-1").buffs().get("potion_quality_bonus"),
                "B-beta-1 (残留時間UP・スプラッシュ強度UP) reuses the same potion_quality_bonus mechanism");
        assertEquals(0.05, tree.nodes().get("C-1-upper").buffs().get("brew_speed_bonus"));
        assertEquals(0.05, tree.nodes().get("C-2-lower").buffs().get("brew_speed_bonus"));
    }
}
