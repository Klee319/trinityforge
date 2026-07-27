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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DiggingGimmickConfig}: {@code durability-exp.durability-per-percent}(2026-07-25、digging.yml
 * C-1/C-2 シャベル耐久累計EXP)のデフォルト/明示上書き/非正値ガードを検証する。同じリフレクション偽
 * {@link Plugin} パターン({@link WoodcuttingGimmickConfigTest}と同型)。
 */
class DiggingGimmickConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("DiggingGimmickConfigTest");
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

    private static DiggingGimmickConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, DiggingGimmickConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        DiggingGimmickConfig config = new DiggingGimmickConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void defaultWhenAbsent(@TempDir File tempDir) throws IOException {
        DiggingGimmickConfig config = loaded(tempDir, "# empty\n");
        assertEquals(100.0, config.durabilityPerPercent(), 1e-9);
        assertTrue(config.dropTables().isEmpty());
    }

    @Test
    void explicitOverride(@TempDir File tempDir) throws IOException {
        DiggingGimmickConfig config = loaded(tempDir, "durability-exp:\n  durability-per-percent: 50\n");
        assertEquals(50.0, config.durabilityPerPercent(), 1e-9);
    }

    @Test
    void nonPositiveFallsBackToDefault(@TempDir File tempDir) throws IOException {
        DiggingGimmickConfig config = loaded(tempDir, "durability-exp:\n  durability-per-percent: 0\n");
        assertEquals(100.0, config.durabilityPerPercent(), 1e-9);
    }

    // --- 2026-07-28 数値のギミックyml集約: durability-exp.vanilla-exp/job-exp の独立tiersテーブル ---

    @Test
    void vanillaAndJobExpCapPercentDefaultToZeroWhenUndefined(@TempDir File tempDir) throws IOException {
        DiggingGimmickConfig config = loaded(tempDir, "# empty\n");
        assertEquals(0.0, config.vanillaExpCapPercent(1), 1e-9);
        assertEquals(0.0, config.jobExpCapPercent(1), 1e-9);
    }

    @Test
    void vanillaAndJobExpCapPercentResolveIndependentTierTables(@TempDir File tempDir) throws IOException {
        DiggingGimmickConfig config = loaded(tempDir, """
                durability-exp:
                  durability-per-percent: 100
                  vanilla-exp:
                    tiers:
                      1: { cap-percent: 50 }
                  job-exp:
                    tiers:
                      1: { cap-percent: 25 }
                """);
        assertEquals(50.0, config.vanillaExpCapPercent(1), 1e-9);
        assertEquals(25.0, config.jobExpCapPercent(1), 1e-9);
        // 完全一致もフロアも無い(tier<1)なら無効(0)。
        assertEquals(0.0, config.vanillaExpCapPercent(0), 1e-9);
    }

    @Test
    void tierAboveHighestDefinedFloorsToTheHighestRow(@TempDir File tempDir) throws IOException {
        DiggingGimmickConfig config = loaded(tempDir, """
                durability-exp:
                  vanilla-exp:
                    tiers:
                      1: { cap-percent: 50 }
                      2: { cap-percent: 70 }
                """);
        assertEquals(70.0, config.vanillaExpCapPercent(2), 1e-9);
        assertEquals(70.0, config.vanillaExpCapPercent(5), 1e-9, "floor resolve: 5 -> tier 2 row");
        assertEquals(50.0, config.vanillaExpCapPercent(1), 1e-9);
    }

    @Test
    void durabilityPerPercentOverrideFallsBackToGlobalWhenRowOmitsIt(@TempDir File tempDir) throws IOException {
        DiggingGimmickConfig config = loaded(tempDir, """
                durability-exp:
                  durability-per-percent: 100
                  vanilla-exp:
                    tiers:
                      1: { cap-percent: 50 }
                  job-exp:
                    tiers:
                      1: { cap-percent: 25, durability-per-percent: 120 }
                """);
        assertEquals(100.0, config.durabilityPerPercentForVanillaExp(1), 1e-9, "row omits override -> global default");
        assertEquals(120.0, config.durabilityPerPercentForJobExp(1), 1e-9, "row overrides the global default");
    }

    @Test
    void malformedTierRowIsSkippedWithoutThrowing(@TempDir File tempDir) throws IOException {
        DiggingGimmickConfig config = loaded(tempDir, """
                durability-exp:
                  vanilla-exp:
                    tiers:
                      1: { cap-percent: -5 }
                      notanumber: { cap-percent: 60 }
                """);
        assertEquals(0.0, config.vanillaExpCapPercent(1), 1e-9);
    }
}
