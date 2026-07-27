package com.trinityforge.config.domains;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CraftingFeaturesConfig}: {@code potion-merge.tiers}(2026-07-26 tier-expand)の tier 解決/
 * 後方互換フォールバックを検証する。他のtier対応configテストと同じリフレクション偽 {@link Plugin} パターン
 * ({@code MiningGimmickConfigTest}と同型)。
 */
class CraftingFeaturesConfigPotionMergeTierTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CraftingFeaturesConfigPotionMergeTierTest");
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
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void tieredAccessorsFallBackToGlobalScalarWhenTiersUndefined(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                potion-merge:
                  max-effects: 5
                  max-duration-seconds: 960
                """);
        // 2026-07-26 tier-expand: tiers 未定義なら完全後方互換。
        assertEquals(5, config.potionMergeMaxEffects(1));
        assertEquals(5, config.potionMergeMaxEffects(99));
        assertEquals(960, config.potionMergeMaxDurationSeconds(1));
    }

    @Test
    void tieredAccessorsResolveFloorEntryFromTiersTable(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                potion-merge:
                  max-effects: 5
                  max-duration-seconds: 960
                  tiers:
                    1: { max-effects: 5, max-duration-seconds: 960 }
                    2: { max-effects: 7, max-duration-seconds: 1200 }
                """);
        assertEquals(5, config.potionMergeMaxEffects(1));
        assertEquals(7, config.potionMergeMaxEffects(2));
        assertEquals(7, config.potionMergeMaxEffects(99));
        assertEquals(1200, config.potionMergeMaxDurationSeconds(2));
    }

    @Test
    void malformedTierRowIsSkippedWithoutThrowing(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                potion-merge:
                  max-effects: 5
                  max-duration-seconds: 960
                  tiers:
                    1: { max-effects: -1, max-duration-seconds: 960 }
                    notanumber: { max-effects: 10, max-duration-seconds: 1200 }
                """);
        assertEquals(5, config.potionMergeMaxEffects(1));
        assertEquals(960, config.potionMergeMaxDurationSeconds(1));
    }
}
