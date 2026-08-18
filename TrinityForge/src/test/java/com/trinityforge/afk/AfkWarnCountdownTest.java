package com.trinityforge.afk;

import com.trinityforge.config.domains.AfkConfig;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AFK 予告カウントダウン(2026-08-18 ユーザー報告「AFK が現状訪れるので title 等でカウントダウンか
 * 通知を表示してほしい」)の回帰テスト。
 *
 * <p>判定は {@link AfkService#warnStateFor} という純関数へ切り出してある。表示(タイトル/アクションバー)と
 * 混ぜたままだと MockBukkit のプレイヤーを立てないと 1 行も検証できず、MockBukkit は未実装 API を
 * <b>失敗ではなく中断(SKIPPED)</b>に化けさせるので「緑なのに一度も走っていない」状態になりやすい。
 */
class AfkWarnCountdownTest {

    private static final long IDLE = 300_000L;   // idle-seconds: 300
    private static final long KICK = 1_800_000L; // kick-after-seconds: 1800
    private static final long WARN = 30_000L;    // warn-before-seconds: 30

    @Test
    @DisplayName("予告窓に入る前は何も出さない")
    void nothingBeforeTheWarnWindow() {
        // 残り 31 秒。窓(30秒)の外。
        assertNull(AfkService.warnStateFor(false, IDLE - 31_000L, IDLE, KICK, WARN));
    }

    @Test
    @DisplayName("予告窓に入ったら AFK 判定までの残り秒を返す")
    void countsDownToTheAfkVerdict() {
        AfkService.WarnState state = AfkService.warnStateFor(false, IDLE - 30_000L, IDLE, KICK, WARN);

        assertNotNull(state, "残り30秒はちょうど窓の内側");
        assertEquals(30, state.remainingSeconds());
        assertEquals(AfkService.WarnStage.BEFORE_AFK, state.stage());
    }

    @Test
    @DisplayName("残り秒は切り上げる(「あと0秒」と出したまま消えないため)")
    void remainingSecondsRoundUp() {
        AfkService.WarnState state = AfkService.warnStateFor(false, IDLE - 400L, IDLE, KICK, WARN);

        assertNotNull(state);
        assertEquals(1, state.remainingSeconds(), "残り0.4秒は「あと1秒」");
    }

    @Test
    @DisplayName("AFK 判定に達した後は AFK 前の予告を出さない")
    void stopsOnceTheDeadlinePassed() {
        assertNull(AfkService.warnStateFor(false, IDLE, IDLE, KICK, WARN));
        assertNull(AfkService.warnStateFor(false, IDLE + 5_000L, IDLE, KICK, WARN));
    }

    @Test
    @DisplayName("AFK 中はキックまでを数える")
    void countsDownToTheKickWhileAfk() {
        AfkService.WarnState state = AfkService.warnStateFor(true, KICK - 10_000L, IDLE, KICK, WARN);

        assertNotNull(state);
        assertEquals(10, state.remainingSeconds());
        assertEquals(AfkService.WarnStage.BEFORE_KICK, state.stage());
    }

    @Test
    @DisplayName("キックしない設定なら AFK 中は何も出さない(何も起きないのに数えない)")
    void noKickCountdownWhenKickIsDisabled() {
        assertNull(AfkService.warnStateFor(true, 10_000_000L, IDLE, 0L, WARN));
    }

    @Test
    @DisplayName("warn-before-seconds が0なら一切出さない")
    void disabledWhenWarnWindowIsZero() {
        assertNull(AfkService.warnStateFor(false, IDLE - 1_000L, IDLE, KICK, 0L));
        assertNull(AfkService.warnStateFor(true, KICK - 1_000L, IDLE, KICK, 0L));
    }

    // ---- AfkConfig 側 ----

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("AfkWarnCountdownTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() はファイルが既にある場合に呼んではいけない");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static AfkConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, AfkConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        AfkConfig config = new AfkConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    @DisplayName("キー未記載でも予告は既定で有効(30秒・タイトルあり)")
    void warnDefaultsAreOn(@TempDir File tempDir) throws IOException {
        AfkConfig config = loaded(tempDir, "enabled: true\n");

        assertEquals(30, config.warnBeforeSeconds());
        assertTrue(config.warnTitle());
    }

    @Test
    @DisplayName("idle-seconds 以上の予告は idle-seconds-1 へ引き下げる(常時カウントダウン防止)")
    void warnIsClampedBelowIdle(@TempDir File tempDir) throws IOException {
        AfkConfig config = loaded(tempDir, "idle-seconds: 60\nwarn-before-seconds: 120\n");

        assertEquals(59, config.warnBeforeSeconds(),
                "予告が idle-seconds 以上だとログインした瞬間から出続ける");
    }

    @Test
    @DisplayName("0 は「予告しない」としてそのまま尊重する")
    void zeroWarnIsKept(@TempDir File tempDir) throws IOException {
        AfkConfig config = loaded(tempDir, "idle-seconds: 60\nwarn-before-seconds: 0\n");

        assertEquals(0, config.warnBeforeSeconds());
    }

    @Test
    @DisplayName("出荷 afk.yml に予告キーが載っている(未記載だと editor から保存した瞬間に落ちる)")
    void shippedYamlDeclaresTheWarnKeys() throws IOException {
        File shipped = new File("src/main/resources/afk.yml");
        assertTrue(shipped.isFile(), "出荷 afk.yml が見つからない: " + shipped.getAbsolutePath());
        String body = Files.readString(shipped.toPath());

        assertTrue(body.contains("warn-before-seconds:"), "warn-before-seconds が出荷 yml に無い");
        assertTrue(body.contains("warn-title:"), "warn-title が出荷 yml に無い");
    }
}
