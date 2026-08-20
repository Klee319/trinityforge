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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code combat/display.yml} の {@code damage-indicator-particles.max-count} パース検証
 * (2026-07-28 実サーバ報告「与ダメージが大きいので被弾時のハート型パーティクルが大量に出る」対応)。
 *
 * <p>負値は全て {@link DisplayConfig#DAMAGE_INDICATOR_UNLIMITED}(-1) へ正規化される — 呼び出し側の
 * {@code DamageIndicatorParticleLimiter} が「{@code < 0} なら何もしない」で分岐できるようにするため。
 */
class DisplayConfigDamageIndicatorTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("DisplayConfigDamageIndicatorTest");
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

    private static DisplayConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, DisplayConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        DisplayConfig config = new DisplayConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "config must parse cleanly");
        return config;
    }

    @Test
    @DisplayName("キー未指定なら既定の上限が入る")
    void defaultsWhenAbsent(@TempDir File tempDir) throws IOException {
        DisplayConfig config = loaded(tempDir, "focus-hp:\n  enabled: true\n");

        assertEquals(DisplayConfig.DEFAULT_DAMAGE_INDICATOR_MAX_COUNT, config.damageIndicatorMaxCount());
    }

    @Test
    @DisplayName("明示した上限をそのまま読む")
    void readsExplicitLimit(@TempDir File tempDir) throws IOException {
        DisplayConfig config = loaded(tempDir, "damage-indicator-particles:\n  max-count: 2\n");

        assertEquals(2, config.damageIndicatorMaxCount());
    }

    @Test
    @DisplayName("0 は「完全に消す」としてそのまま保持する(無制限へ丸めない)")
    void zeroMeansFullySuppressed(@TempDir File tempDir) throws IOException {
        DisplayConfig config = loaded(tempDir, "damage-indicator-particles:\n  max-count: 0\n");

        assertEquals(0, config.damageIndicatorMaxCount());
    }

    @Test
    @DisplayName("-1 は「制限しない」")
    void minusOneMeansUnlimited(@TempDir File tempDir) throws IOException {
        DisplayConfig config = loaded(tempDir, "damage-indicator-particles:\n  max-count: -1\n");

        assertEquals(DisplayConfig.DAMAGE_INDICATOR_UNLIMITED, config.damageIndicatorMaxCount());
    }

    @Test
    @DisplayName("-1 以外の負値も「制限しない」へ正規化する")
    void otherNegativesNormalizeToUnlimited(@TempDir File tempDir) throws IOException {
        DisplayConfig config = loaded(tempDir, "damage-indicator-particles:\n  max-count: -50\n");

        assertEquals(DisplayConfig.DAMAGE_INDICATOR_UNLIMITED, config.damageIndicatorMaxCount());
    }

    @Test
    @DisplayName("出荷ymlの既定値がコード側の既定値と一致している(ドリフト検知)")
    void shippedYamlMatchesCodeDefault() throws IOException {
        String shipped = Files.readString(
                new File("src/main/resources/" + DisplayConfig.PATH).toPath());

        assertTrue(shipped.contains("max-count: " + DisplayConfig.DEFAULT_DAMAGE_INDICATOR_MAX_COUNT),
                "combat/display.yml の max-count が DisplayConfig の既定値とずれている");
    }
}
