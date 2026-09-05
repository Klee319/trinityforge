package com.trinityforge.config.domains;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code progression/level-broadcast.yml} のパース検査 (2026-08-16)。
 *
 * <p>ここで固定しているのは「設定ミスがそのまま事故になる」3 点。
 * <ol>
 *   <li>{@code multiple-of: 0} を通すと {@code level % 0} で {@code ArithmeticException} になり、
 *       レベルアップのたびに例外が飛ぶ（アナウンスどころか受け手全体が壊れる）。</li>
 *   <li>{@code include-power} の既定が false であること。true に化けると 1 回のレベルアップで
 *       「採掘 Lv30」「総合 Lv30」の 2 行が連続して流れる。</li>
 *   <li>除外リストと節目判定が 1 箇所（{@link LevelBroadcastConfig#shouldAnnounce}）に集約されていること。
 *       リスナー側で条件を書き直すと片方の経路だけ除外が効かなくなる。</li>
 * </ol>
 */
class LevelBroadcastConfigTest {

    @TempDir
    File tempDir;

    static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("LevelBroadcastConfigTest");
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

    /** {@code yaml} を一時データフォルダへ書いてから読み込む。テスト間で使い回せる。 */
    static LevelBroadcastConfig loaded(File dataFolder, String yaml) throws IOException {
        File file = new File(dataFolder, LevelBroadcastConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml, StandardCharsets.UTF_8);
        LevelBroadcastConfig config = new LevelBroadcastConfig();
        assertTrue(config.load(fakePlugin(dataFolder)), "読み込みに失敗した");
        return config;
    }

    @Test
    @DisplayName("multiple-of: 0 は既定(10)へ戻す（0 除算で例外にしない）")
    void multipleOfZeroFallsBackToDefault() throws IOException {
        LevelBroadcastConfig config = loaded(tempDir, "multiple-of: 0\n");

        assertEquals(10, config.multipleOf());
        // 丸めが無いと以下の呼び出し自体が ArithmeticException になる。
        assertTrue(config.shouldAnnounce("MINING", 10));
        assertFalse(config.shouldAnnounce("MINING", 11));
    }

    @Test
    @DisplayName("節目は multiple-of の倍数だけ")
    void onlyMultiplesAreAnnounced() throws IOException {
        LevelBroadcastConfig config = loaded(tempDir, "multiple-of: 25\n");

        assertFalse(config.shouldAnnounce("MINING", 10));
        assertTrue(config.shouldAnnounce("MINING", 25));
        assertTrue(config.shouldAnnounce("MINING", 50));
        assertFalse(config.shouldAnnounce("MINING", 0), "Lv0 は到達扱いしない");
    }

    @Test
    @DisplayName("POWER(総合)は既定でアナウンスしない / include-power: true で解禁される")
    void powerIsExcludedByDefault() throws IOException {
        LevelBroadcastConfig off = loaded(tempDir, "multiple-of: 10\n");
        assertFalse(off.shouldAnnounce("POWER", 10), "既定で総合を流すと1回のレベルアップで2連続告知になる");
        assertFalse(off.shouldAnnounce("power", 10), "大文字小文字を問わず除外する");
        assertTrue(off.shouldAnnounce("MINING", 10), "他スキルは通常どおり流す");

        LevelBroadcastConfig on = loaded(tempDir, "multiple-of: 10\ninclude-power: true\n");
        assertTrue(on.shouldAnnounce("POWER", 10));
    }

    @Test
    @DisplayName("excluded-skills / excluded-levels は大文字小文字を問わず効く")
    void exclusionsAreApplied() throws IOException {
        LevelBroadcastConfig config = loaded(tempDir, """
                multiple-of: 10
                excluded-skills:
                  - fishing
                excluded-levels:
                  - 20
                """);

        assertFalse(config.shouldAnnounce("FISHING", 10));
        assertFalse(config.shouldAnnounce("fishing", 30));
        assertFalse(config.shouldAnnounce("MINING", 20));
        assertTrue(config.shouldAnnounce("MINING", 10));
    }

    @Test
    @DisplayName("enabled: false は節目判定ごと止める")
    void disabledStopsEverything() throws IOException {
        LevelBroadcastConfig config = loaded(tempDir, "enabled: false\n");

        assertFalse(config.enabled());
        assertFalse(config.shouldAnnounce("MINING", 10));
    }

    @Test
    @DisplayName("上限とサウンドの数値は範囲へ丸める")
    void numericValuesAreClamped() throws IOException {
        LevelBroadcastConfig config = loaded(tempDir, """
                max-announcements-per-batch: 0
                sound:
                  volume: -5.0
                  pitch: 9.0
                """);

        assertEquals(1, config.maxAnnouncementsPerBatch(), "0 だと1行も出せない");
        assertEquals(0.0f, config.soundVolume());
        assertEquals(2.0f, config.soundPitch(), "Minecraft のピッチ有効域は 0.5〜2.0");
    }

    @Test
    @DisplayName("message を空にしても既定の書式へ戻す（空行を配らない）")
    void blankMessageFallsBackToDefault() throws IOException {
        LevelBroadcastConfig config = loaded(tempDir, "message: \"\"\n");

        assertTrue(config.message().contains("%player%"), config.message());
        assertTrue(config.message().contains("%level%"), config.message());
    }

    @Test
    @DisplayName("min-interval-seconds は 0〜3600 へ丸め、既定は30")
    void minIntervalSecondsIsClampedAndDefaults() throws IOException {
        LevelBroadcastConfig defaults = loaded(tempDir, "enabled: true\n");
        assertEquals(30, defaults.minIntervalSeconds());

        LevelBroadcastConfig negative = loaded(tempDir, "min-interval-seconds: -5\n");
        assertEquals(0, negative.minIntervalSeconds());

        LevelBroadcastConfig huge = loaded(tempDir, "min-interval-seconds: 999999\n");
        assertEquals(3600, huge.minIntervalSeconds());

        LevelBroadcastConfig disabled = loaded(tempDir, "min-interval-seconds: 0\n");
        assertEquals(0, disabled.minIntervalSeconds(), "0 は無効を意味する正当な設定値");
    }

    @Test
    @DisplayName("message-prestige を空にしても既定の書式(%prestige%入り)へ戻す")
    void blankMessagePrestigeFallsBackToDefault() throws IOException {
        LevelBroadcastConfig config = loaded(tempDir, "message-prestige: \"\"\n");

        assertTrue(config.messagePrestige().contains("%prestige%"), config.messagePrestige());
        assertTrue(config.messagePrestige().contains("%player%"), config.messagePrestige());
        assertTrue(config.messagePrestige().contains("%level%"), config.messagePrestige());
    }

    @Test
    @DisplayName("出荷 yml がそのまま読めて、既定値が意図どおりである")
    void shippedFileParsesWithIntendedDefaults() throws IOException {
        String shipped;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(LevelBroadcastConfig.PATH)) {
            assertNotNull(in, "出荷 yml が classpath に無い: " + LevelBroadcastConfig.PATH);
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        LevelBroadcastConfig config = loaded(tempDir, shipped);

        assertTrue(config.enabled());
        assertEquals(10, config.multipleOf());
        assertFalse(config.includePower(), "出荷既定で総合(POWER)を流すと2連続告知になる");
        assertTrue(config.soundEnabled());
        assertEquals("UI_TOAST_CHALLENGE_COMPLETE", config.sound());
        assertTrue(config.message().contains("%player%"), config.message());
        assertTrue(config.message().contains("%skill%"), config.message());
        assertTrue(config.message().contains("%level%"), config.message());
        assertTrue(config.excludedSkills().isEmpty());
        assertTrue(config.excludedLevels().isEmpty());
    }
}
