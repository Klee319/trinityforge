package com.trinityforge.config.domains;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * バニラの「奇妙なポーション」(水入り瓶＋ネザーウォート)の醸造に、TF が一切干渉していないことを
 * 固定する(2026-08-25, W-235)。
 *
 * <h2>なぜこのテストが要るのか</h2>
 * <p>「奇妙なポーションが作れない」という報告があり、Java 版でも起きるのかを追検証した。
 * 出荷ソースを全走査した結果は<b>白</b>で、根拠は次の3つ:
 * <ul>
 *   <li>TF 本体の主ソースに {@code AWKWARD} という語は<b>1つも無い</b>
 *       (出てくるのはテストとフォークの GUI 表示ラベルだけ)</li>
 *   <li>解放式ポーションの門({@code brew-unlocks})が見ているベースは
 *       {@code THICK} と {@code MUNDANE} だけで、<b>{@code WATER} を見る枠が1つも無い</b>。
 *       この門は「ベースの種類＋素材」の組で弾くので、{@code WATER} の枠が無い限り
 *       水入り瓶＋ネザーウォートには構造的に当たらない</li>
 *   <li>錬金のEXP表は {@code AWKWARD} に 150 を配っている＝作れる前提の設定になっている</li>
 * </ul>
 *
 * <p>ここで固定するのは<b>2つ目</b>。1つ目と3つ目は別のファイル(主ソース / スキルEXP表)の話で、
 * それぞれ別のガードが見ている。門に {@code WATER} の枠を1つ足すと、
 * <b>「解放するまでバニラの奇妙なポーションが作れないサーバ」</b>に無言で変わる ——
 * 起動ログにも何も出ず、プレイヤーからは「醸造台が壊れている」としか見えない。
 * その1行を足した瞬間にここで落ちてほしい。
 *
 * <p>⚠ カスタム効果ポーションの醸造禁止(同日追加)がここに当たらないことは
 * {@code PotionCustomEffectBrewGuardTest} が別途固定している(素の水入り瓶は通す)。
 */
class ShippedVanillaAwkwardBrewIsUntouchedTest {

    private CraftingFeaturesConfig config;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws IOException {
        MockBukkit.mock();
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (var in = ShippedVanillaAwkwardBrewIsUntouchedTest.class.getClassLoader()
                .getResourceAsStream(CraftingFeaturesConfig.PATH.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + CraftingFeaturesConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        CraftingFeaturesConfig loaded = new CraftingFeaturesConfig();
        assertTrue(loaded.load(fakePlugin(tempDir)), "出荷 crafting-features.yml がパースできない");
        this.config = loaded;
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("解放式ポーションの門は WATER ベースを1枠も見ない(＝奇妙なポーションを塞がない)")
    void noBrewUnlockGatesTheWaterBase() {
        Map<String, BrewUnlockGroup> groups = config.brewUnlocks();
        assertFalse(groups.isEmpty(), "brew-unlocks が1件も読めていない(読み取りが壊れている?)");

        List<BrewPotionSpec> specs = groups.values().stream()
                .flatMap(group -> group.potions().stream())
                .toList();
        assertTrue(specs.size() >= 10,
                "解放式ポーションの枠が少なすぎる(読み取りが壊れている?): " + specs.size() + " 枠");

        List<String> waterGates = specs.stream()
                .filter(spec -> "WATER".equalsIgnoreCase(spec.base()))
                .map(spec -> spec.base() + " + " + spec.ingredient())
                .toList();
        assertEquals(List.of(), waterGates,
                "解放式ポーションの門が WATER ベースを見ている。この門は「ベースの種類＋素材」の組で"
                        + "醸造を弾くので、WATER の枠を作ると解放するまで"
                        + "バニラの奇妙なポーション(水入り瓶＋ネザーウォート)まで巻き込んで作れなくなる。"
                        + "警告もエラーも出ないので、実機では『醸造台が壊れている』としか見えない: "
                        + waterGates);
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedVanillaAwkwardBrewIsUntouchedTest");
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
}
