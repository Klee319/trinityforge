package com.trinityforge.progression;

import com.trinityforge.config.domains.LevelBroadcastConfig;
import com.trinityforge.progression.core.SkillProgress;
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
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * プレステージ(NG+)の登り直しで全体アナウンスが氾濫する不具合の修正固定 (2026-09-04, 台帳 W-313)。
 *
 * <p>真因は {@link SkillLevelBroadcastListener} がプレステージ段を一切見ていなかったこと。
 * {@code NativePerkService#prestigeUnderLock} はレベルを 0 へ戻すだけなので、放送側で
 * 何も抑制しないと周回のたびに同じ節目(10〜100)が全体へ流れる（実測: 90秒で14行）。
 * ここで固定するのは 2 点。
 * <ol>
 *   <li>プレステージ段が1以上のスキルは、上限レベル到達だけを放送する。</li>
 *   <li>{@code min-interval-seconds} によるプレイヤー単位の流量制限（原因が何であれ
 *       チャットが埋まること自体を止める最後の砦）。</li>
 * </ol>
 */
class SkillLevelBroadcastPrestigeTest {

    private ServerMock server;
    private Plugin plugin;

    @TempDir
    File dataFolder;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("LevelBroadcastPrestigeTest");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // --- プレステージ段による抑制 ------------------------------------------------------------

    @Test
    @DisplayName("段0(未プレステージ)は従来どおり全ての節目が流れる（回帰）")
    void tierZeroAnnouncesEveryMilestone() throws IOException {
        BiFunction<UUID, String, SkillProgress> lookup =
                (id, skill) -> new SkillProgress(80, 0.0, 0.0, 0, 100);
        PlayerMock player = register("multiple-of: 10\nmin-interval-seconds: 0\n", lookup);

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(player.getUniqueId(), "MINING", 79, 80);
        server.getScheduler().performTicks(2);

        assertEquals(1, messages(player).size(), "段0はLv80のような通常の節目でも流れる");
    }

    @Test
    @DisplayName("段3のLv80到達は流れず、上限のLv100だけが段番号入りで流れる")
    void prestigedSkillOnlyAnnouncesAtCap() throws IOException {
        BiFunction<UUID, String, SkillProgress> lookup =
                (id, skill) -> new SkillProgress(0, 0.0, 0.0, 3, 100);
        PlayerMock player = register("multiple-of: 10\nmin-interval-seconds: 0\n", lookup);

        BukkitSkillLevelUpDispatcher dispatcher = new BukkitSkillLevelUpDispatcher(plugin);
        dispatcher.onSkillLevelUp(player.getUniqueId(), "MINING", 79, 80);
        server.getScheduler().performTicks(2);
        assertTrue(messages(player).isEmpty(),
                "段3のLv80(節目だが上限ではない)は登り直しの途中経過なので流してはいけない");

        dispatcher.onSkillLevelUp(player.getUniqueId(), "MINING", 99, 100);
        server.getScheduler().performTicks(2);

        List<String> lines = messages(player);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("Lv100"), lines.get(0));
        assertTrue(lines.get(0).contains("3周目"), "段番号がメッセージに出ていない: " + lines.get(0));
    }

    @Test
    @DisplayName("修正前の証明: 供給元が未配線だと段3でもLv80が普通に流れる（両建て）")
    void withoutPrestigeLookupTheSameLv80AnnouncesAnyway() throws IOException {
        // register() ではなく 3引数コンストラクタ(=skillProgressLookup 未配線)を直接使う。
        // 未配線は「段が読めない」と同じ扱い(安全側=段0)になるため、修正前の全量放送を再現する。
        LevelBroadcastConfig config = loadConfig("multiple-of: 10\nmin-interval-seconds: 0\n");
        SkillLevelBroadcastListener listener =
                new SkillLevelBroadcastListener(plugin, config, skillId -> "採掘");
        server.getPluginManager().registerEvents(listener, plugin);
        PlayerMock player = server.addPlayer();
        drain(player);

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(player.getUniqueId(), "MINING", 79, 80);
        server.getScheduler().performTicks(2);

        assertEquals(1, messages(player).size(),
                "供給元が無ければ段0扱いなので、実際にプレステージ済みでもLv80が流れてしまう"
                        + "（=このテストが緑のままだと、上のテストが本当に効いている証明にならない）");
    }

    @Test
    @DisplayName("供給元が例外を投げても放送機構は落ちず、段0として扱う")
    void lookupExceptionFallsBackToTierZero() throws IOException {
        BiFunction<UUID, String, SkillProgress> lookup = (id, skill) -> {
            throw new RuntimeException("boom");
        };
        PlayerMock player = register("multiple-of: 10\nmin-interval-seconds: 0\n", lookup);

        new BukkitSkillLevelUpDispatcher(plugin).onSkillLevelUp(player.getUniqueId(), "MINING", 79, 80);
        server.getScheduler().performTicks(2);

        assertEquals(1, messages(player).size(), "供給元の例外で放送そのものが消えてはいけない");
    }

    // --- 流量制限 (min-interval-seconds) ----------------------------------------------------

    @Test
    @DisplayName("min-interval-seconds: 30 なら、連続する節目到達でも1行だけ")
    void minIntervalThrottlesRepeatedAnnouncements() throws IOException {
        PlayerMock player = register("multiple-of: 10\nmin-interval-seconds: 30\n", null);

        BukkitSkillLevelUpDispatcher dispatcher = new BukkitSkillLevelUpDispatcher(plugin);
        dispatcher.onSkillLevelUp(player.getUniqueId(), "MINING", 9, 10);
        server.getScheduler().performTicks(2);
        dispatcher.onSkillLevelUp(player.getUniqueId(), "FARMING", 19, 20);
        server.getScheduler().performTicks(2);
        dispatcher.onSkillLevelUp(player.getUniqueId(), "FISHING", 29, 30);
        server.getScheduler().performTicks(2);

        assertEquals(1, messages(player).size(),
                "間隔内の2件目以降は黙って捨てる(チャットが埋まることの最後の砦)");
    }

    @Test
    @DisplayName("min-interval-seconds: 0 なら流量制限は無効")
    void minIntervalZeroDisablesThrottle() throws IOException {
        PlayerMock player = register("multiple-of: 10\nmin-interval-seconds: 0\n", null);

        BukkitSkillLevelUpDispatcher dispatcher = new BukkitSkillLevelUpDispatcher(plugin);
        dispatcher.onSkillLevelUp(player.getUniqueId(), "MINING", 9, 10);
        server.getScheduler().performTicks(2);
        dispatcher.onSkillLevelUp(player.getUniqueId(), "FARMING", 19, 20);
        server.getScheduler().performTicks(2);

        assertEquals(2, messages(player).size());
    }

    @Test
    @DisplayName("流量制限はプレイヤー単位。別プレイヤーは巻き添えにしない")
    void minIntervalIsPerPlayer() throws IOException {
        LevelBroadcastConfig config = loadConfig("multiple-of: 10\nmin-interval-seconds: 30\n");
        SkillLevelBroadcastListener listener =
                new SkillLevelBroadcastListener(plugin, config, skillId -> "採掘", null);
        server.getPluginManager().registerEvents(listener, plugin);
        PlayerMock a = server.addPlayer();
        PlayerMock b = server.addPlayer();
        drain(a);
        drain(b);

        BukkitSkillLevelUpDispatcher dispatcher = new BukkitSkillLevelUpDispatcher(plugin);
        dispatcher.onSkillLevelUp(a.getUniqueId(), "MINING", 9, 10);
        dispatcher.onSkillLevelUp(b.getUniqueId(), "MINING", 9, 10);
        server.getScheduler().performTicks(2);

        // 全体放送なので、両者とも「aの到達」と「bの到達」の2行を受け取る。
        // ここで確かめたいのは行数ではなく、b の到達が a の流量制限に巻き込まれて
        // 消されていないこと(消えていれば 1 行にしかならない)。
        assertEquals(2, messages(a).size(), "a自身の流量制限でbの到達まで消えてはいけない");
        assertEquals(2, messages(b).size(), "b自身の流量制限でaの到達まで消えてはいけない");
    }

    @Test
    @DisplayName("ログアウトで流量制限の記録が掃除され、再参加後は即座に放送できる")
    void quitClearsThrottleState() throws IOException {
        LevelBroadcastConfig config = loadConfig("multiple-of: 10\nmin-interval-seconds: 30\n");
        SkillLevelBroadcastListener listener =
                new SkillLevelBroadcastListener(plugin, config, skillId -> "採掘", null);
        server.getPluginManager().registerEvents(listener, plugin);
        PlayerMock player = server.addPlayer();
        UUID id = player.getUniqueId();
        String name = player.getName();
        drain(player);

        BukkitSkillLevelUpDispatcher dispatcher = new BukkitSkillLevelUpDispatcher(plugin);
        dispatcher.onSkillLevelUp(id, "MINING", 9, 10);
        server.getScheduler().performTicks(2);
        assertEquals(1, messages(player).size());

        player.disconnect(); // PlayerQuitEvent を発火 → listener.onQuit が記録を消す

        PlayerMock rejoined = new PlayerMock(server, name, id);
        server.addPlayer(rejoined);
        drain(rejoined);

        dispatcher.onSkillLevelUp(id, "FARMING", 19, 20);
        server.getScheduler().performTicks(2);

        assertEquals(1, messages(rejoined).size(),
                "掃除されていなければ30秒以内として黙って捨てられ、ここが0件になっていたはず");
    }

    // --- helpers --------------------------------------------------------------------------------

    private PlayerMock register(String yaml, BiFunction<UUID, String, SkillProgress> lookup)
            throws IOException {
        LevelBroadcastConfig config = loadConfig(yaml);
        SkillLevelBroadcastListener listener =
                new SkillLevelBroadcastListener(plugin, config, skillId -> "採掘", lookup);
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
            case "getLogger" -> Logger.getLogger("SkillLevelBroadcastPrestigeTest");
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
