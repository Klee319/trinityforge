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
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code scrap-conversion.<sourceId>}(2026-08-08新設, C-1) のパースと重み付き抽選を検証する。
 * ルール自体の重み解決アルゴリズムは {@link DisassemblyRule#pick} を再利用しているだけなので、
 * ここでは「そのアルゴリズムに正しい入力(base-amount + outputs)が渡っているか」だけを見る。
 */
class CraftingFeaturesConfigScrapConversionTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CraftingFeaturesConfigScrapConversionTest");
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
    @DisplayName("base-amount + outputs が DisassemblyRule として読める")
    void parsesBaseAmountAndOutputs(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                scrap-conversion:
                  tf_scrap:
                    base-amount: 4
                    outputs:
                      - item: custom:copper_ingot_scrap
                        weight: 25
                      - item: custom:netherite_ingot_scrap
                        weight: 1
                """);
        DisassemblyRule rule = config.scrapConversion("tf_scrap");
        assertNotNull(rule);
        assertTrue(rule.hasBaseAmount());
        assertEquals(4.0, rule.baseAmount());
        assertEquals(2, rule.outputs().size());
    }

    @Test
    @DisplayName("base-amount を欠いた行は消費量を決める術が無いので読み込まれない")
    void missingBaseAmountIsSkipped(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                scrap-conversion:
                  tf_scrap:
                    outputs:
                      - item: custom:copper_ingot_scrap
                        weight: 1
                """);
        assertNull(config.scrapConversion("tf_scrap"));
    }

    @Test
    @DisplayName("未定義の source id は null (機構全体が無効ではなく、その id だけ無干渉)")
    void unknownSourceIdReturnsNull(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                scrap-conversion:
                  tf_scrap:
                    base-amount: 4
                    outputs:
                      - item: custom:copper_ingot_scrap
                        weight: 1
                """);
        assertNull(config.scrapConversion("not_registered"));
    }

    /**
     * 出荷 yml の {@code scrap-conversion.tf_scrap} が、ユーザー確定の重み表(合計100)と一致することを
     * 固定する。値そのものはユーザー指定(2026-08-08)なので、ここでは「その値が壊れていないか」
     * (件数・重みの合計・各エントリの重み)だけを見る。
     */
    @Test
    @DisplayName("出荷 yml の tf_scrap 変換先は7件・重み合計100・確定値どおり")
    void shippedScrapConversionMatchesConfirmedWeights(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (var in = CraftingFeaturesConfigScrapConversionTest.class.getClassLoader()
                .getResourceAsStream(CraftingFeaturesConfig.PATH.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + CraftingFeaturesConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "出荷 crafting-features.yml がパースできない");

        DisassemblyRule rule = config.scrapConversion("tf_scrap");
        assertNotNull(rule, "出荷 yml に scrap-conversion.tf_scrap が無い");
        assertEquals(4.0, rule.baseAmount());

        Map<String, Double> weightByItem = new java.util.LinkedHashMap<>();
        for (DisassemblyOutput output : rule.outputs()) {
            weightByItem.put(output.item(), output.weight());
        }
        assertEquals(Set.of(
                "custom:copper_ingot_scrap", "custom:iron_ingot_scrap", "custom:leather_scrap",
                "custom:gold_ingot_scrap", "custom:turtle_scute_scrap", "custom:diamond_scrap",
                "custom:netherite_ingot_scrap"), weightByItem.keySet());
        assertEquals(25.0, weightByItem.get("custom:copper_ingot_scrap"));
        assertEquals(25.0, weightByItem.get("custom:iron_ingot_scrap"));
        assertEquals(20.0, weightByItem.get("custom:leather_scrap"));
        assertEquals(12.0, weightByItem.get("custom:gold_ingot_scrap"));
        assertEquals(12.0, weightByItem.get("custom:turtle_scute_scrap"));
        assertEquals(5.0, weightByItem.get("custom:diamond_scrap"));
        assertEquals(1.0, weightByItem.get("custom:netherite_ingot_scrap"));
        double total = weightByItem.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(100.0, total, "重みの合計はユーザー確定値の100であるはず");
    }

    @Test
    @DisplayName("重み付き抽選(pick)は最小weight(netherite)が最も出づらい側に位置する")
    void pickRespectsConfiguredWeights() {
        DisassemblyRule rule = new DisassemblyRule("custom:tf_scrap", java.util.List.of(
                new DisassemblyOutput("custom:copper_ingot_scrap", 25, 1.0),
                new DisassemblyOutput("custom:iron_ingot_scrap", 25, 1.0),
                new DisassemblyOutput("custom:leather_scrap", 20, 1.0),
                new DisassemblyOutput("custom:gold_ingot_scrap", 12, 1.0),
                new DisassemblyOutput("custom:turtle_scute_scrap", 12, 1.0),
                new DisassemblyOutput("custom:diamond_scrap", 5, 1.0),
                new DisassemblyOutput("custom:netherite_ingot_scrap", 1, 1.0)
        ), 1.0, 4.0);

        // roll=0.0 は累積重みの先頭(copper)、roll のすぐ手前が netherite(最後の1/100)。
        assertEquals("custom:copper_ingot_scrap", rule.pick(0.0).item());
        assertEquals("custom:netherite_ingot_scrap", rule.pick(0.999).item());
    }
}
