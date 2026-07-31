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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // --- 2026-07-31 N2 葉の枚数上限/段階破壊/崩壊判定 ---

    @Test
    void leafDefaultsWhenNoLeafKeysArePresent(@TempDir File tempDir) throws IOException {
        // 配備先ymlが古くて葉のキーが1つも無いサーバでも、Java既定で「速い」側に倒れること。
        WoodcuttingGimmickConfig config = loaded(tempDir, "tree-fell:\n  max-extra-logs: 8\n");

        assertTrue(config.treeFellBreakLeaves());
        assertEquals(512, config.treeFellLeavesMax());
        assertEquals(48, config.treeFellLeavesPerTick());
        assertTrue(config.treeFellLeavesDecayOnly());
        assertEquals(512, config.treeFellMaxLeaves(1, 3), "tiers未定義ならグローバルへフォールバック");
        // 2026-07-31 G1 指摘6b: scan-limit の Java 既定値は TreeScan の定数と一致していること
        // (片方だけ変えると「jar だけ配備したサーバ」で走査範囲が変わる)。
        assertEquals(com.trinityforge.woodcutting.TreeScan.TREE_SCAN_LIMIT, config.treeFellScanLimit());
    }

    // --- 2026-07-31 G1 レビュー指摘6b: tree-fell.scan-limit の config 化 ---

    @Test
    void scanLimitCanBeLoweredFromYamlAndNonPositiveFallsBackToTheDefault(@TempDir File tempDir)
            throws IOException {
        assertEquals(64, loaded(tempDir, """
                tree-fell:
                  max-extra-logs: 8
                  scan-limit: 64
                """).treeFellScanLimit(), "明示値がそのまま効くこと");
        assertEquals(512, loaded(tempDir, """
                tree-fell:
                  max-extra-logs: 8
                  scan-limit: 0
                """).treeFellScanLimit(), "0以下は既定へ戻す(走査ゼロで一括伐採が無言で死ぬのを防ぐ)");
    }

    @Test
    void tierLeavesMaxWinsOverTheGlobalValue(@TempDir File tempDir) throws IOException {
        WoodcuttingGimmickConfig config = loaded(tempDir, """
                tree-fell:
                  max-extra-logs: 8
                  leaves-max: 512
                  tiers:
                    1: { max-extra-logs: 8, leaves-max: 128 }
                    3: { max-extra-logs: 32 }
                """);

        assertEquals(128, config.treeFellMaxLeaves(1, 3), "tier行の leaves-max が最優先");
        assertEquals(128, config.treeFellMaxLeaves(2, 3), "floor解決なのでtier2はtier1行を引く");
        assertEquals(512, config.treeFellMaxLeaves(3, 3),
                "leaves-max を書いていないtier行はグローバルへフォールバック");
    }

    @Test
    void nonPositiveGlobalLeavesMaxFallsBackToTheLegacyPerLogBehaviour(@TempDir File tempDir)
            throws IOException {
        // 旧キー leaves-per-log は「leaves-max を0以下にしたときだけ効く」後方互換の逃げ道。
        WoodcuttingGimmickConfig config = loaded(tempDir, """
                tree-fell:
                  max-extra-logs: 8
                  leaves-max: 0
                  leaves-per-log: 6
                """);

        assertEquals(0, config.treeFellLeavesMax());
        assertEquals(6, config.treeFellLeavesPerLog());
        assertEquals(18, config.treeFellMaxLeaves(1, 3), "伐った原木3本 × 6 = 18枚(旧挙動)");
        assertEquals(0, config.treeFellMaxLeaves(1, 0), "伐った本数0なら0枚");
    }

    @Test
    void leavesPerTickAndDecayOnlyCanBeTurnedOffFromYaml(@TempDir File tempDir) throws IOException {
        WoodcuttingGimmickConfig config = loaded(tempDir, """
                tree-fell:
                  max-extra-logs: 8
                  leaves-per-tick: 0
                  leaves-decay-only: false
                """);

        assertEquals(0, config.treeFellLeavesPerTick(), "0以下は「同tickで全部壊す」の意味なので保つこと");
        assertFalse(config.treeFellLeavesDecayOnly());
    }

    @Test
    void shippedYamlCarriesTheTierLeafCapsAndPerTickBudget(@TempDir File tempDir) throws IOException {
        // ハードコードした既定値ではなく出荷ymlの実バイトを読む(既定値を検証するテストは
        // 出荷値のドリフトを捕まえられない)。
        File source = new File("src/main/resources/" + WoodcuttingGimmickConfig.PATH);
        File dest = new File(tempDir, WoodcuttingGimmickConfig.PATH);
        Files.createDirectories(dest.getParentFile().toPath());
        Files.copy(source.toPath(), dest.toPath());
        WoodcuttingGimmickConfig config = new WoodcuttingGimmickConfig();
        config.load(fakePlugin(tempDir));

        assertEquals(128, config.treeFellMaxLeaves(1, 0), "tier1 = 128枚");
        assertEquals(256, config.treeFellMaxLeaves(2, 0), "tier2 = 256枚");
        assertEquals(512, config.treeFellMaxLeaves(3, 0), "tier3 = 512枚");
        assertEquals(1024, config.treeFellMaxLeaves(4, 0), "tier4 = 1024枚");
        assertEquals(48, config.treeFellLeavesPerTick(), "1tickあたり48枚");
        assertTrue(config.treeFellLeavesDecayOnly(), "既定でバニラの崩壊判定に従うこと");
        assertTrue(config.treeFellBreakLeaves());
        assertEquals(512, config.treeFellScanLimit(), "出荷値の scan-limit");
        // 2026-07-31 G1 レビュー指摘2: 8/16/32/64 から倍にした。decay-only(既定true)は
        // 「バニラなら崩壊する葉」しか壊さないので、幹を伐り切れない木では葉が1枚も壊れない。
        // 「葉の掃除がバニラより大幅に速く」を満たす道は decay-only を緩めることではなく
        // 幹を伐り切ることなので、本数上限を上げるのが正しいレバー。
        assertEquals(16, config.treeFellMaxExtraLogs(1));
        assertEquals(32, config.treeFellMaxExtraLogs(2));
        assertEquals(64, config.treeFellMaxExtraLogs(3));
        assertEquals(128, config.treeFellMaxExtraLogs(4));
    }
}
