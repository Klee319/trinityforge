package com.trinityforge.progression;

import com.trinityforge.config.domains.LevelBroadcastConfig;
import com.trinityforge.progression.event.BukkitSkillLevelUpDispatcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 節目レベルアップの全体アナウンス（{@code progression/level-broadcast.yml}）の挙動固定 (2026-08-16)。
 *
 * <p>ここで守っているのは「アナウンスが氾濫しない」ことと「除外設定が実経路で効く」こと。
 * {@code TrinitySkillLevelUpEvent} は<b>到達レベルごとに 1 回</b>発火するので、素直に書くと
 * 1 回の EXP 付与で節目の数だけ行が流れる。畳み込み（(プレイヤー, スキル) ごとに最高到達レベルだけ）と
 * 1 フラッシュあたりの上限を外すと、このクラスのテストが落ちる。
 */
class SkillLevelBroadcastListenerTest {

    private ServerMock server;
    private Plugin plugin;

    @TempDir
    File dataFolder;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("LevelBroadcastTest");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // --- 氾濫抑止 -------------------------------------------------------------------------------

    @Test
    @DisplayName("1回の付与で複数の節目を跨いでも、流れるのは最高到達レベルの1行だけ")
    void multiLevelJumpCollapsesToTheHighestMilestone() throws IOException {
        PlayerMock player = register("multiple-of: 10\n");

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(player.getUniqueId(), "MINING", 9, 31);
        // フラッシュは 1 tick 後。実装の tick 境界の解釈差で取りこぼさないよう 2 tick 進める。
        server.getScheduler().performTicks(2);

        List<String> lines = messages(player);
        assertEquals(1, lines.size(), "Lv10/20/30 の3行が流れてはいけない: " + lines);
        assertTrue(lines.get(0).contains("Lv30"),
                "最初の節目(Lv10)ではなく最高到達(Lv30)を出す: " + lines.get(0));
    }

    @Test
    @DisplayName("節目に届かないレベルアップでは何も流れない")
    void nonMilestoneLevelsAreSilent() throws IOException {
        PlayerMock player = register("multiple-of: 10\n");

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(player.getUniqueId(), "MINING", 5, 9);
        // フラッシュは 1 tick 後。実装の tick 境界の解釈差で取りこぼさないよう 2 tick 進める。
        server.getScheduler().performTicks(2);

        assertTrue(messages(player).isEmpty());
    }

    @Test
    @DisplayName("同一tickに多数のスキルが節目へ届いても max-announcements-per-batch で頭打ちになる")
    void batchIsCapped() throws IOException {
        // min-interval-seconds は既定30だと同一プレイヤーへの2件目以降を別機構(流量制限)で
        // 落としてしまい、この上限テストの意図(バッチ上限そのもの)とかぶるため無効化する。
        // 流量制限自体は SkillLevelBroadcastPrestigeTest で別途固定している。
        PlayerMock player = register(
                "multiple-of: 10\nmax-announcements-per-batch: 2\nmin-interval-seconds: 0\n");

        BukkitSkillLevelUpDispatcher dispatcher = new BukkitSkillLevelUpDispatcher(plugin);
        for (String skill : List.of("MINING", "FARMING", "FISHING", "DIGGING")) {
            dispatcher.onSkillLevelUp(player.getUniqueId(), skill, 9, 10);
        }
        // フラッシュは 1 tick 後。実装の tick 境界の解釈差で取りこぼさないよう 2 tick 進める。
        server.getScheduler().performTicks(2);

        assertEquals(2, messages(player).size(), "上限を超えた分は捨てる（管理コマンドの一括付与対策）");
    }

    // --- 除外 -----------------------------------------------------------------------------------

    @Test
    @DisplayName("総合(POWER)は既定でアナウンスしない")
    void powerIsSilentByDefault() throws IOException {
        PlayerMock player = register("multiple-of: 10\n");

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(player.getUniqueId(), "POWER", 9, 10);
        // フラッシュは 1 tick 後。実装の tick 境界の解釈差で取りこぼさないよう 2 tick 進める。
        server.getScheduler().performTicks(2);

        assertTrue(messages(player).isEmpty(),
                "POWER は他スキルのレベルアップから派生する導出値。既定で流すと2連続告知になる");
    }

    @Test
    @DisplayName("include-power: true なら総合(POWER)も流れる")
    void powerCanBeOptedIn() throws IOException {
        PlayerMock player = register("multiple-of: 10\ninclude-power: true\n");

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(player.getUniqueId(), "POWER", 9, 10);
        // フラッシュは 1 tick 後。実装の tick 境界の解釈差で取りこぼさないよう 2 tick 進める。
        server.getScheduler().performTicks(2);

        assertEquals(1, messages(player).size());
    }

    @Test
    @DisplayName("excluded-skills / excluded-levels は実経路でも効く")
    void exclusionsApplyOnTheRealPath() throws IOException {
        PlayerMock player = register("""
                multiple-of: 10
                excluded-skills:
                  - fishing
                excluded-levels:
                  - 20
                """);

        BukkitSkillLevelUpDispatcher dispatcher = new BukkitSkillLevelUpDispatcher(plugin);
        dispatcher.onSkillLevelUp(player.getUniqueId(), "FISHING", 9, 10);
        dispatcher.onSkillLevelUp(player.getUniqueId(), "MINING", 19, 20);
        // フラッシュは 1 tick 後。実装の tick 境界の解釈差で取りこぼさないよう 2 tick 進める。
        server.getScheduler().performTicks(2);

        assertTrue(messages(player).isEmpty());
    }

    @Test
    @DisplayName("enabled: false なら一切流れない")
    void disabledSendsNothing() throws IOException {
        PlayerMock player = register("enabled: false\n");

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(player.getUniqueId(), "MINING", 9, 10);
        // フラッシュは 1 tick 後。実装の tick 境界の解釈差で取りこぼさないよう 2 tick 進める。
        server.getScheduler().performTicks(2);

        assertTrue(messages(player).isEmpty());
    }

    // --- 表示 -----------------------------------------------------------------------------------

    @Test
    @DisplayName("全体アナウンスなので、レベルを上げていない他プレイヤーにも届く")
    void everyOnlinePlayerReceivesTheLine() throws IOException {
        PlayerMock leveler = register("multiple-of: 10\n");
        PlayerMock bystander = server.addPlayer();
        drain(bystander);

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(leveler.getUniqueId(), "MINING", 9, 10);
        // フラッシュは 1 tick 後。実装の tick 境界の解釈差で取りこぼさないよう 2 tick 進める。
        server.getScheduler().performTicks(2);

        assertEquals(1, messages(leveler).size());
        assertEquals(1, messages(bystander).size(), "全体アナウンスが本人にしか届いていない");
    }

    @Test
    @DisplayName("message は MiniMessage として描画される（タグが生のまま出ない）")
    void messageIsRenderedAsMiniMessage() throws IOException {
        PlayerMock player = register(
                "multiple-of: 10\nmessage: \"<gold>%player%</gold> <aqua>%skill%</aqua> Lv%level%\"\n");

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(player.getUniqueId(), "MINING", 9, 10);
        // フラッシュは 1 tick 後。実装の tick 境界の解釈差で取りこぼさないよう 2 tick 進める。
        server.getScheduler().performTicks(2);

        List<String> lines = messages(player);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.contains(player.getName()), line);
        assertTrue(line.contains("採掘"), "スキル表示名が差し込まれていない: " + line);
        assertTrue(line.contains("Lv10"), line);
        assertTrue(!line.contains("<gold>") && !line.contains("</aqua>"),
                "MiniMessage タグが生のまま表示されている: " + line);
    }

    @Test
    @DisplayName("%player% / %level% を欠いた書式は既定の書式へ落として必ず情報を残す")
    void malformedFormatFallsBackInsteadOfLosingInformation() throws IOException {
        PlayerMock player = register("multiple-of: 10\nmessage: \"おめでとう!\"\n");

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(player.getUniqueId(), "MINING", 9, 10);
        // フラッシュは 1 tick 後。実装の tick 境界の解釈差で取りこぼさないよう 2 tick 進める。
        server.getScheduler().performTicks(2);

        List<String> lines = messages(player);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains(player.getName()), lines.get(0));
        assertTrue(lines.get(0).contains("Lv10"), lines.get(0));
    }

    // --- サウンド -------------------------------------------------------------------------------

    @Test
    @DisplayName("解決できないサウンド名でも例外にせず、アナウンス自体は流れる（無音に倒す）")
    void unresolvableSoundStillAnnounces() throws IOException {
        PlayerMock player = register("multiple-of: 10\nsound:\n  key: \"BAD KEY WITH SPACES\"\n");

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(player.getUniqueId(), "MINING", 9, 10);
        // フラッシュは 1 tick 後。実装の tick 境界の解釈差で取りこぼさないよう 2 tick 進める。
        server.getScheduler().performTicks(2);

        assertEquals(1, messages(player).size(), "音が鳴らせないだけで祝う行まで消してはいけない");
    }

    @Test
    @DisplayName("サウンド名の解決: 記法として壊れているものだけ null に倒す")
    void soundKeyResolution() {
        assertNull(SkillLevelBroadcastListener.soundKey(null));
        assertNull(SkillLevelBroadcastListener.soundKey("   "));
        assertNull(SkillLevelBroadcastListener.soundKey("BAD KEY WITH SPACES"));
        assertNotNull(SkillLevelBroadcastListener.soundKey("UI_TOAST_CHALLENGE_COMPLETE"),
                "出荷既定の音名が解決できないと、既定設定のまま無音になる");
    }

    // --- helpers --------------------------------------------------------------------------------

    /** yml を書いて読み、リスナーを登録し、プレイヤーを1人参加させて受信箱を空にして返す。 */
    private PlayerMock register(String yaml) throws IOException {
        LevelBroadcastConfig config = loadConfig(yaml);
        SkillLevelBroadcastListener listener =
                new SkillLevelBroadcastListener(plugin, config, skillId -> "採掘");
        server.getPluginManager().registerEvents(listener, plugin);
        PlayerMock player = server.addPlayer();
        drain(player);
        return player;
    }

    private LevelBroadcastConfig loadConfig(String yaml) throws IOException {
        File file = new File(dataFolder, LevelBroadcastConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml, StandardCharsets.UTF_8);
        LevelBroadcastConfig config = new LevelBroadcastConfig();
        assertTrue(config.load(fakePlugin(dataFolder)), "level-broadcast.yml の読み込みに失敗した");
        return config;
    }

    private static Plugin fakePlugin(File folder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> folder;
            case "getLogger" -> Logger.getLogger("SkillLevelBroadcastListenerTest");
            case "saveResource" -> throw new AssertionError("ファイルは事前に書いてある");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static void drain(PlayerMock player) {
        while (player.nextComponentMessage() != null) {
            // 参加メッセージ等を捨てる
        }
    }

    private static List<String> messages(PlayerMock player) {
        List<String> out = new ArrayList<>();
        for (Component msg = player.nextComponentMessage(); msg != null;
                msg = player.nextComponentMessage()) {
            out.add(PlainTextComponentSerializer.plainText().serialize(msg));
        }
        return out;
    }
}
