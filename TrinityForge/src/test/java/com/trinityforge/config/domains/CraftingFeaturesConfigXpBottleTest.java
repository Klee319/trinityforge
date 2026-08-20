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
 * {@link CraftingFeaturesConfig}: {@code xp-bottle-store}(エンチャントツリー「EXPフリーザー」
 * feature:xp-bottle-store-unlock が参照する数値設定)の既定値/クランプ/tier解決を検証する。
 *
 * <p>2026-08-15 に {@code stats/fishing-gimmick.yml} から {@code progression/crafting-features.yml} へ
 * 移設した際、この6件のテストは {@code FishingGimmickConfigTest} から移設した(挙動は完全に同一 —
 * クランプ規則・floor解決・フォールバックは {@code FishingGimmickConfig} 時代のロジックをそのまま踏襲)。
 * リフレクション偽 {@link Plugin} パターンは {@code CraftingFeaturesConfigPotionMergeTierTest} と同型。
 */
class CraftingFeaturesConfigXpBottleTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CraftingFeaturesConfigXpBottleTest");
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
    void xpBottleReturnRateDefaultsTo1WhenAbsent(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, "xp-bottle-store:\n  store-amount: 100\n");
        assertEquals(1.0, config.xpBottleReturnRate(), 0.0);
    }

    @Test
    void xpBottleReturnRateHonorsExplicitValue(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                xp-bottle-store:
                  store-amount: 100
                  return-rate: 0.9
                """);
        assertEquals(0.9, config.xpBottleReturnRate(), 0.0);
    }

    @Test
    void xpBottleReturnRateIsClampedTo0To1(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                xp-bottle-store:
                  store-amount: 100
                  return-rate: 1.5
                """);
        assertEquals(1.0, config.xpBottleReturnRate(), 0.0);
    }

    // ---- 2026-07-26 tier-expand: xp-bottle-store.tiers ----

    @Test
    void tieredXpBottleAccessorsFallBackToGlobalScalarWhenTiersUndefined(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                xp-bottle-store:
                  store-amount: 100
                  return-rate: 0.9
                """);
        assertEquals(100, config.xpBottleStoreAmount(1));
        assertEquals(100, config.xpBottleStoreAmount(99));
        assertEquals(0.9, config.xpBottleReturnRate(1), 0.0);
    }

    @Test
    void tieredXpBottleAccessorsResolveFloorEntryFromTiersTable(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                xp-bottle-store:
                  store-amount: 100
                  return-rate: 1.0
                  tiers:
                    1: { store-amount: 100, return-rate: 0.8 }
                    2: { store-amount: 200, return-rate: 1.0 }
                """);
        assertEquals(100, config.xpBottleStoreAmount(1));
        assertEquals(0.8, config.xpBottleReturnRate(1), 0.0);
        assertEquals(200, config.xpBottleStoreAmount(2));
        assertEquals(1.0, config.xpBottleReturnRate(2), 0.0);
        assertEquals(200, config.xpBottleStoreAmount(99), "floor resolve above the highest defined tier");
    }

    @Test
    void malformedXpBottleTierRowIsSkippedWithoutThrowing(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                xp-bottle-store:
                  store-amount: 100
                  return-rate: 1.0
                  tiers:
                    1: { store-amount: -5, return-rate: 0.8 }
                    2: { store-amount: 200, return-rate: 1.5 }
                    notanumber: { store-amount: 300, return-rate: 0.5 }
                """);
        // All three rows invalid -> table stays empty -> falls back to the global scalar.
        assertEquals(100, config.xpBottleStoreAmount(1));
        assertEquals(1.0, config.xpBottleReturnRate(1), 0.0);
    }
}
