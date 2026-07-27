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
 * {@link WoodcuttingGimmickConfig} のデフォルト/明示上書き/tiers解決を検証する。同じリフレクション偽
 * {@link Plugin} パターン({@link MiningGimmickConfigTest} と同型)。
 */
class WoodcuttingGimmickConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("WoodcuttingGimmickConfigTest");
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

    private static WoodcuttingGimmickConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, WoodcuttingGimmickConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        WoodcuttingGimmickConfig config = new WoodcuttingGimmickConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void defaultsWhenSectionsAbsent(@TempDir File tempDir) throws IOException {
        WoodcuttingGimmickConfig config = loaded(tempDir, "tree-fell:\n  max-extra-logs: 8\n");
        assertEquals(8, config.treeFellMaxExtraLogs());
        assertEquals(200, config.treeFellCooldownTicks());
        assertTrue(config.dropTables().isEmpty());
        // tiers未定義 -> 完全後方互換フォールバック。
        assertEquals(8, config.treeFellMaxExtraLogs(1));
        assertEquals(8, config.treeFellMaxExtraLogs(99));
    }

    @Test
    void migratedTiersMatchOldSmallLargeDefaults(@TempDir File tempDir) throws IOException {
        // 2026-07-25 gather-rework-active-framework §6 Q1 migration: tier1=8(旧small既定),
        // tier3=64(旧large既定) — woodcutting.yml B/D ノードの移行値と対応。
        WoodcuttingGimmickConfig config = loaded(tempDir, """
                tree-fell:
                  max-extra-logs: 8
                  cooldown-ticks: 200
                  tiers:
                    1: { max-extra-logs: 8 }
                    3: { max-extra-logs: 64 }
                """);
        assertEquals(8, config.treeFellMaxExtraLogs(1));
        assertEquals(8, config.treeFellMaxExtraLogs(2));
        assertEquals(64, config.treeFellMaxExtraLogs(3));
        assertEquals(64, config.treeFellMaxExtraLogs(99));
    }

    @Test
    void nonPositiveTickValuesFallBackToDefault(@TempDir File tempDir) throws IOException {
        WoodcuttingGimmickConfig config = loaded(tempDir, """
                tree-fell:
                  max-extra-logs: -5
                  cooldown-ticks: 0
                """);
        assertEquals(8, config.treeFellMaxExtraLogs());
        assertEquals(200, config.treeFellCooldownTicks());
    }
}
