package com.trinityforge.config.domains;

import com.trinityforge.config.domains.CraftingFeaturesConfig.DisassemblyOutput;
import com.trinityforge.config.domains.CraftingFeaturesConfig.DisassemblyRule;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解体(disassembly)の 2026-07-27 拡張:
 * アイテム個別指定 / {@code outputs} の重み抽選 / クラフトレシピを持たないアイテム向けの
 * {@code base-amount} を検証する。従来形({@code output} 単体・{@code input} のみ)が
 * 無改変で動き続けることも合わせて確認する。
 */
class CraftingFeaturesConfigDisassemblyTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CraftingFeaturesConfigDisassemblyTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static CraftingFeaturesConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "config must parse cleanly");
        return config;
    }

    @Test
    @DisplayName("従来形(input + output)は単一候補のルールとしてそのまま読める")
    void legacySingleOutput(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                disassembly:
                  percent-per-level: 25
                  items:
                    iron_*:
                      - input: IRON_INGOT
                        output: custom:iron_ingot_scrap
                        multiplier: 2
                """);
        List<DisassemblyRule> rules = config.disassemblyRulesFor("iron_sword");
        assertEquals(1, rules.size());
        DisassemblyRule rule = rules.get(0);
        assertEquals("IRON_INGOT", rule.input());
        assertEquals("custom:iron_ingot_scrap", rule.output());
        assertEquals(2.0, rule.multiplier());
        assertFalse(rule.hasBaseAmount(), "base-amount 未指定ならレシピから数える");
    }

    @Test
    @DisplayName("* を含まないキーはアイテム個別指定として完全一致する")
    void exactItemKeyIsIndividualTarget(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                disassembly:
                  items:
                    ROTTEN_FLESH:
                      - base-amount: 1
                        output: custom:leather_scrap
                """);
        assertEquals(1, config.disassemblyRulesFor("ROTTEN_FLESH").size());
        assertTrue(config.disassemblyRulesFor("ROTTEN_FLESH_X").isEmpty(),
                "完全一致キーは前方一致してはいけない");
    }

    @Test
    @DisplayName("base-amount を指定するとレシピを引かずに材料数が決まる")
    void baseAmountReplacesRecipeLookup(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                disassembly:
                  items:
                    LILY_PAD:
                      - base-amount: 3
                        output: custom:plank_scrap
                """);
        DisassemblyRule rule = config.disassemblyRulesFor("LILY_PAD").get(0);
        assertTrue(rule.hasBaseAmount());
        assertEquals(3.0, rule.baseAmount());
        assertNull(rule.input(), "base-amount 使用時は input を要求しない");
    }

    @Test
    @DisplayName("outputs は重み付き候補として読まれ、候補ごとの multiplier を上書きできる")
    void weightedOutputsParse(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                disassembly:
                  items:
                    BONE:
                      - base-amount: 1
                        multiplier: 2
                        outputs:
                          - item: custom:leather_scrap
                            weight: 3
                          - item: custom:iron_ingot_scrap
                            weight: 1
                            multiplier: 5
                """);
        DisassemblyRule rule = config.disassemblyRulesFor("BONE").get(0);
        assertEquals(2, rule.outputs().size());
        assertEquals(3.0, rule.outputs().get(0).weight());
        assertEquals(2.0, rule.outputs().get(0).multiplier(), "候補側未指定ならルールの multiplier");
        assertEquals(5.0, rule.outputs().get(1).multiplier(), "候補側指定が優先される");
    }

    @Test
    @DisplayName("pick は重みに比例して1件だけ選ぶ")
    void pickSelectsOneWeightedOutput() {
        DisassemblyRule rule = new DisassemblyRule(null, List.of(
                new DisassemblyOutput("A", 3.0, 1.0),
                new DisassemblyOutput("B", 1.0, 1.0)), 1.0, 1.0);
        // 合計4。0.00〜0.75がA、0.75〜1.00がB。
        assertEquals("A", rule.pick(0.0).item());
        assertEquals("A", rule.pick(0.5).item());
        assertEquals("B", rule.pick(0.9).item());
        assertEquals("B", rule.pick(1.0).item());
    }

    @Test
    @DisplayName("重み0以下の候補は抽選から外れ、全候補が無効なら戻りなし(null)")
    void zeroWeightCandidatesAreExcluded() {
        DisassemblyRule mixed = new DisassemblyRule(null, List.of(
                new DisassemblyOutput("A", 0.0, 1.0),
                new DisassemblyOutput("B", 1.0, 1.0)), 1.0, 1.0);
        assertEquals("B", mixed.pick(0.0).item());
        assertEquals("B", mixed.pick(1.0).item());

        DisassemblyRule allZero = new DisassemblyRule(null, List.of(
                new DisassemblyOutput("A", 0.0, 1.0),
                new DisassemblyOutput("B", 0.0, 1.0)), 1.0, 1.0);
        assertNull(allZero.pick(0.5), "全候補が無効重み → 素材を消費させないため null");
    }

    @Test
    @DisplayName("出荷ymlの釣りゴミ定義がそのまま読める(実データの回帰)")
    void shippedFishingJunkEntriesLoad(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (var in = getClass().getClassLoader()
                .getResourceAsStream(CraftingFeaturesConfig.PATH.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + CraftingFeaturesConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        assertTrue(config.load(fakePlugin(tempDir)));
        DisassemblyRule rotten = config.disassemblyRulesFor("ROTTEN_FLESH").get(0);
        assertTrue(rotten.hasBaseAmount(), "レシピを持たないゴミは base-amount で定義されている");
        assertTrue(rotten.outputs().size() > 1, "重み抽選になっている");
    }
}
