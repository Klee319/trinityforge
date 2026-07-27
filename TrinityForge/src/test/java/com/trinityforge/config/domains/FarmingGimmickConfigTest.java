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
 * {@link FarmingGimmickConfig} のデフォルト/明示上書き/無効値ガードを検証する。他のconfigローダーテストと
 * 同じ、リフレクションで作る偽{@link Plugin}パターン({@link MiningGimmickConfigTest}参照)。
 */
class FarmingGimmickConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("FarmingGimmickConfigTest");
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

    private static FarmingGimmickConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, FarmingGimmickConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        FarmingGimmickConfig config = new FarmingGimmickConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void defaultsWhenSectionsAbsent(@TempDir File tempDir) throws IOException {
        FarmingGimmickConfig config = loaded(tempDir, "area-harvest:\n  radius: 1\n");
        assertEquals(1, config.areaHarvestRadius());
        assertEquals(4.0, config.animalDamageMultiplier(), 0.0);
        assertEquals(8.0, config.beeCalmRadius(), 0.0);
    }

    @Test
    void honorsExplicitOverrides(@TempDir File tempDir) throws IOException {
        FarmingGimmickConfig config = loaded(tempDir, """
                area-harvest:
                  radius: 2
                animal-damage-4x:
                  multiplier: 6.0
                bee-no-aggro:
                  calm-radius: 12.5
                """);
        assertEquals(2, config.areaHarvestRadius());
        assertEquals(6.0, config.animalDamageMultiplier(), 0.0);
        assertEquals(12.5, config.beeCalmRadius(), 0.0);
    }

    @Test
    void nonPositiveValuesFallBackToDefault(@TempDir File tempDir) throws IOException {
        FarmingGimmickConfig config = loaded(tempDir, """
                area-harvest:
                  radius: -1
                animal-damage-4x:
                  multiplier: 0.0
                bee-no-aggro:
                  calm-radius: -5.0
                """);
        assertEquals(1, config.areaHarvestRadius());
        assertEquals(4.0, config.animalDamageMultiplier(), 0.0);
        assertEquals(8.0, config.beeCalmRadius(), 0.0);
    }

    @Test
    void nonFiniteMultiplierFallsBackToDefault(@TempDir File tempDir) throws IOException {
        FarmingGimmickConfig config = loaded(tempDir, """
                animal-damage-4x:
                  multiplier: .NaN
                """);
        assertEquals(4.0, config.animalDamageMultiplier(), 0.0);
    }

    @Test
    void areaHarvestTierFallsBackToGlobalScalarWhenTiersUndefined(@TempDir File tempDir) throws IOException {
        // 2026-07-25 gather-rework-active-framework §1 item 2: tiers 未定義なら完全後方互換。
        FarmingGimmickConfig config = loaded(tempDir, "area-harvest:\n  radius: 1\n");
        assertEquals(1, config.areaHarvestRadius(1));
        assertEquals(1, config.areaHarvestRadius(99));
    }

    @Test
    void areaHarvestTierResolvesFloorEntryFromTiersTable(@TempDir File tempDir) throws IOException {
        FarmingGimmickConfig config = loaded(tempDir, """
                area-harvest:
                  radius: 1
                  tiers:
                    1: { radius: 1 }
                    3: { radius: 2 }
                """);
        assertEquals(1, config.areaHarvestRadius(1));
        assertEquals(1, config.areaHarvestRadius(2));
        assertEquals(2, config.areaHarvestRadius(3));
        assertEquals(2, config.areaHarvestRadius(99));
    }
}
