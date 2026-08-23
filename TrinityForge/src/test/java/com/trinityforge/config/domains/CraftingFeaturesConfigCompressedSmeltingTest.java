package com.trinityforge.config.domains;

import com.trinityforge.config.domains.CraftingFeaturesConfig.CompressedSmelt;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code compressed-smelting.<入力素材id>}(2026-08-23 新設)のパースを検証する。
 *
 * <p>壊れた行を<b>黙って既定へ倒さない</b>ことがこの機能の要件 —— 倒すと
 * 「焼けるはずが焼けない」だけの無言の穴になり、かまどの前で切り分ける手段が無くなる。
 */
class CraftingFeaturesConfigCompressedSmeltingTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CraftingFeaturesConfigCompressedSmeltingTest");
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
    @DisplayName("result と cook-time が読める / experience は省略時 3.15")
    void parsesResultAndCookTime(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                compressed-smelting:
                  potato_1x:
                    result: baked_potato_1x
                    cook-time: 1800
                """);
        CompressedSmelt smelt = config.compressedSmelt("potato_1x");
        assertNotNull(smelt);
        assertEquals("baked_potato_1x", smelt.resultId());
        assertEquals(1800, smelt.cookTime());
        assertEquals(3.15f, smelt.experience(), 0.0001f);
    }

    @Test
    @DisplayName("燻製器はかまどの半分、焚き火は3倍(バニラの 200:100:600 と同じ比率)")
    void derivesSmokerAndCampfireTimes(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                compressed-smelting:
                  beef_1x:
                    result: compressed_cooked_beef_1x
                    cook-time: 1800
                """);
        CompressedSmelt smelt = config.compressedSmelt("beef_1x");
        assertNotNull(smelt);
        assertEquals(900, smelt.smokingTime());
        assertEquals(5400, smelt.campfireTime());
    }

    @Test
    @DisplayName("experience は明示できる / 負値は0へ丸める")
    void readsExplicitExperience(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                compressed-smelting:
                  cod_1x:
                    result: baked_cod_1x
                    cook-time: 1800
                    experience: 1.5
                  salmon_1x:
                    result: baked_salon_1x
                    cook-time: 1800
                    experience: -4.0
                """);
        assertEquals(1.5f, config.compressedSmelt("cod_1x").experience(), 0.0001f);
        assertEquals(0.0f, config.compressedSmelt("salmon_1x").experience(), 0.0001f);
    }

    @Test
    @DisplayName("result 未指定 / cook-time が0以下の行は落とす(既定へ倒さない)")
    void dropsBrokenRows(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                compressed-smelting:
                  no_result:
                    cook-time: 1800
                  zero_time:
                    result: baked_potato_1x
                    cook-time: 0
                  negative_time:
                    result: baked_potato_1x
                    cook-time: -200
                  good:
                    result: baked_potato_1x
                    cook-time: 1800
                """);
        assertNull(config.compressedSmelt("no_result"));
        assertNull(config.compressedSmelt("zero_time"));
        assertNull(config.compressedSmelt("negative_time"));
        assertNotNull(config.compressedSmelt("good"));
        assertEquals(1, config.compressedSmelting().size());
    }

    @Test
    @DisplayName("セクションが無ければ空 / 未登録idと null は null を返す")
    void emptyWhenSectionMissing(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, "potion-merge:\n  max-effects: 5\n");
        assertTrue(config.compressedSmelting().isEmpty());
        assertNull(config.compressedSmelt("potato_1x"));
        assertNull(config.compressedSmelt(null));
    }

    @Test
    @DisplayName("出荷 yml の compressed-smelting が全行そろって読める")
    void shippedConfigParses() {
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        File resources = new File("src/main/resources");
        assertTrue(new File(resources, CraftingFeaturesConfig.PATH).isFile(),
                "出荷 yml が見つからない: " + CraftingFeaturesConfig.PATH);
        assertTrue(config.load(fakePlugin(resources)), "出荷 yml が読めない");
        // 8種(じゃがいも/牛/豚/鶏/羊/ウサギ/タラ/鮭)。増減したらここも更新すること。
        assertEquals(8, config.compressedSmelting().size(),
                "compressed-smelting の行数: " + config.compressedSmelting().keySet());
        for (var entry : config.compressedSmelting().entrySet()) {
            assertTrue(entry.getKey().endsWith("_1x"),
                    "入力は9倍圧縮(_1x)だけのはず: " + entry.getKey());
            assertTrue(entry.getValue().cookTime() > 200,
                    "バニラ(200tick)より短い圧縮精錬は取り分が増えてしまう: " + entry.getKey());
        }
    }
}
