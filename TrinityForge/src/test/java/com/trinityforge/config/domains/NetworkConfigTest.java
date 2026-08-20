package com.trinityforge.config.domains;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code network.yml} のパース検査 (2026-08-15)。
 *
 * <p>この config は 3 台でジャンクション共有されるので、サーバ固有の値を持てない。
 * 表示名は「プロキシが名乗ったサーバ名 → 表示名」の対応表で引くしかなく、
 * <b>対応表から漏れた名前が空文字になると「どこの発言か分からない行」が出る</b>。
 * その一点をここで固定する。
 */
class NetworkConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("NetworkConfigTest");
            case "saveResource" -> throw new AssertionError(
                    "ファイルが既にあるので saveResource() は呼ばれないはず");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static NetworkConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, NetworkConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        NetworkConfig config = new NetworkConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "読み込みに失敗した");
        return config;
    }

    @Test
    @DisplayName("出荷時の3サーバは日本語の表示名になる")
    void displayName_shippedServers_useJapaneseLabels() throws IOException {
        NetworkConfig config = new NetworkConfig();

        assertEquals("メイン", config.displayName("main"));
        assertEquals("資源", config.displayName("resource"));
        assertEquals("開発", config.displayName("dev"));
    }

    @Test
    @DisplayName("対応表に無いサーバ名は空にせずサーバ名のまま出す")
    void displayName_unmappedServer_fallsBackToRawName() throws IOException {
        NetworkConfig config = loaded(tempYaml(), """
                chat:
                  servers:
                    main: "メイン"
                """);

        // 空文字にすると【】だけの行になり、どこの発言か読めなくなる。
        assertEquals("lobby", config.displayName("lobby"));
    }

    @Test
    @DisplayName("サーバ名の大文字小文字は無視する")
    void displayName_isCaseInsensitive() {
        NetworkConfig config = new NetworkConfig();

        assertEquals("資源", config.displayName("RESOURCE"));
    }

    @Test
    @DisplayName("enabled: false はチャット共有と TP をまとめて止める")
    void enabledFalse_disablesBothFeatures() throws IOException {
        NetworkConfig config = loaded(tempYaml(), """
                enabled: false
                chat:
                  enabled: true
                teleport:
                  enabled: true
                """);

        assertFalse(config.chatEnabled(), "enabled: false なのにチャット共有が生きている");
        assertFalse(config.teleportEnabled(), "enabled: false なのに TP が生きている");
    }

    @Test
    @DisplayName("到着待ちの遅延は上下限で丸める")
    void arrivalDelayTicks_isClamped() throws IOException {
        assertEquals(1, loaded(tempYaml(), "teleport:\n  arrival-delay-ticks: 0\n").arrivalDelayTicks());
        assertEquals(200, loaded(tempYaml(), "teleport:\n  arrival-delay-ticks: 99999\n").arrivalDelayTicks());
    }

    @Test
    @DisplayName("書式を空にしても既定へ戻す（空の行を配らない）")
    void chatFormat_blankFallsBackToDefault() throws IOException {
        NetworkConfig config = loaded(tempYaml(), "chat:\n  format: \"\"\n");

        assertTrue(config.chatFormat().contains("%player%"), config.chatFormat());
        assertTrue(config.chatFormat().contains("%message%"), config.chatFormat());
    }

    @Test
    @DisplayName("servers を空にしても既定の対応表を使う")
    void serverDisplayNames_emptySectionFallsBackToDefaults() throws IOException {
        NetworkConfig config = loaded(tempYaml(), "chat:\n  servers: {}\n");

        assertEquals("メイン", config.displayName("main"));
    }

    @TempDir
    File tempDirField;

    private File tempYaml() {
        // 1 テスト内で複数回 load する場合に備えて、毎回同じ一時ディレクトリを使い回す。
        return tempDirField;
    }
}
