package com.trinityforge.progression;

import com.trinityforge.config.domains.SkillExpConfig;
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
 * W-314 タスク3の回帰テスト。進行データ(EXP/レベル/ポイント残高)読みキャッシュのTTLは
 * {@code stats/skill-exp.yml: cache.ttl-seconds} という専用キーで読まれ、
 * {@code progression/combat-level.yml}(戦闘レベル<b>表示</b>専用のキャッシュ)の設定を
 * 流用していた旧配線とは独立していることを検証する。
 */
class ProgressionCacheTtlConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ProgressionCacheTtlConfigTest");
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

    private static SkillExpConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, SkillExpConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        SkillExpConfig config = new SkillExpConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void defaultsToThreeSecondsWhenKeyIsAbsent(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, "{}\n");
        assertEquals(3_000L, config.progressionCacheTtlMillis(),
                "既定値は流用時代の実効値(3秒)と一致していなければ挙動が変わってしまう");
    }

    @Test
    void honorsExplicitTtlSecondsValue(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                cache:
                  ttl-seconds: 30
                """);
        assertEquals(30_000L, config.progressionCacheTtlMillis());
    }

    @Test
    void outOfRangeValueFallsBackToDefault(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                cache:
                  ttl-seconds: 999
                """);
        assertEquals(3_000L, config.progressionCacheTtlMillis(),
                "範囲外(0-300)は既定値へフォールバックしなければならない");
    }

    @Test
    void zeroDisablesCaching(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                cache:
                  ttl-seconds: 0
                """);
        assertEquals(0L, config.progressionCacheTtlMillis());
    }
}
