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

    @Test
    void tieredAccessorFallsBackToGlobalScalarWhenTiersUndefined(@TempDir File tempDir) throws IOException {
        DiggingGimmickConfig config = loaded(tempDir, "durability-exp:\n  durability-per-percent: 80\n");
        // 2026-07-26 tier-expand: tiers 未定義なら完全後方互換。
        assertEquals(80.0, config.durabilityPerPercent(25), 1e-9);
        assertEquals(80.0, config.durabilityPerPercent(50), 1e-9);
    }

    @Test
    void tieredAccessorResolvesFloorEntryFromTiersTable(@TempDir File tempDir) throws IOException {
        DiggingGimmickConfig config = loaded(tempDir, """
                durability-exp:
                  durability-per-percent: 100
                  tiers:
                    25: { durability-per-percent: 120 }
                    50: { durability-per-percent: 80 }
                """);
        assertEquals(100.0, config.durabilityPerPercent(10), 1e-9, "below the lowest tier -> global scalar");
        assertEquals(120.0, config.durabilityPerPercent(25), 1e-9);
        assertEquals(120.0, config.durabilityPerPercent(40), 1e-9, "floor resolve: 40 -> tier 25 row");
        assertEquals(80.0, config.durabilityPerPercent(50), 1e-9);
        assertEquals(80.0, config.durabilityPerPercent(99), 1e-9);
    }

    @Test
    void malformedTierRowIsSkippedWithoutThrowing(@TempDir File tempDir) throws IOException {
        DiggingGimmickConfig config = loaded(tempDir, """
                durability-exp:
                  durability-per-percent: 100
                  tiers:
                    25: { durability-per-percent: -5 }
                    notanumber: { durability-per-percent: 60 }
                """);
        assertEquals(100.0, config.durabilityPerPercent(25), 1e-9);
    }
}
