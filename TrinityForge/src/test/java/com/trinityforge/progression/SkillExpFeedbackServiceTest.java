package com.trinityforge.progression;

import com.trinityforge.combat.ActionBarRouter;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillProgress;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SkillExpFeedbackService}: S5(ボスバー/アクションバー表示)・S6(レベルアップ通知)の回帰テスト
 * (2026-07-25 監査で「表示ロジックに回帰テストが無い」と指摘)。
 *
 * <p>境界固定の方針:
 * <ul>
 *   <li>ボスバー進捗比率({@code progressRatio})・レベルアップ倍数判定({@code isMilestoneLevel})は、
 *       最小限のリファクタで {@link SkillExpFeedbackService} から切り出した純粋関数を直接呼んで固定する
 *       (Adventure の {@code showTitle(Title)} は MockBukkit の {@code PlayerMock} が実オーバーライドを
 *       持たず既定の no-op に落ちるため観測不能 — 呼ばれたかどうかを外側からアサートできない)。</li>
 *   <li>ボスバーの実際の表示内容・アクションバー分岐・レベルアップ時のチャット/サウンドは、
 *       既存テスト(例: {@code DiggingDurabilityExpListenerTest})と同じ MockBukkit スタイルで実際に
 *       {@link SkillExpFeedbackService#onExpGranted} を経由させ、{@code PlayerMock} 側の観測用API
 *       ({@code getBossBars()}, {@code nextActionBar()}, {@code nextMessage()}, {@code getHeardSounds()})
 *       で検証する。</li>
 * </ul>
 */
class SkillExpFeedbackServiceTest {

    private static final String SKILL_ID = "TESTSKILL";

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private SkillExpConfig config;
    private NativeSkillCatalog catalog;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        config = mock(SkillExpConfig.class);
        catalog = mock(NativeSkillCatalog.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // --- progressRatio (ボスバー進捗計算の純粋関数) ---------------------------------------------------

    @Test
    @DisplayName("progressRatio: 通常ケースは residualExp/span")
    void progressRatioNormalCase() {
        assertEquals(0.25f, SkillExpFeedbackService.progressRatio(3, 100, 25.0, 100.0), 1e-6f);
    }

    @Test
    @DisplayName("progressRatio: span<=0 は0除算を避けて1.0を返す")
    void progressRatioZeroSpanReturnsFull() {
        assertEquals(1.0f, SkillExpFeedbackService.progressRatio(3, 100, 25.0, 0.0), 1e-6f);
        assertEquals(1.0f, SkillExpFeedbackService.progressRatio(3, 100, 0.0, -5.0), 1e-6f);
    }

    @Test
    @DisplayName("progressRatio: 最大レベル到達時は1.0")
    void progressRatioMaxLevelReturnsFull() {
        assertEquals(1.0f, SkillExpFeedbackService.progressRatio(100, 100, 0.0, 50.0), 1e-6f);
        assertEquals(1.0f, SkillExpFeedbackService.progressRatio(101, 100, 0.0, 50.0), 1e-6f);
    }

    @Test
    @DisplayName("progressRatio: residualExpがspanを超えても1.0にクランプ")
    void progressRatioOverSpanIsClamped() {
        assertEquals(1.0f, SkillExpFeedbackService.progressRatio(3, 100, 150.0, 100.0), 1e-6f);
    }

    @Test
    @DisplayName("progressRatio: residualExpが負でも0.0未満にはならない(下限クランプ)")
    void progressRatioNegativeResidualIsClampedToZero() {
        assertEquals(0.0f, SkillExpFeedbackService.progressRatio(3, 100, -10.0, 100.0), 1e-6f);
    }

    // --- isMilestoneLevel (レベルアップ・タイトル倍数判定の純粋関数) -----------------------------------

    @ParameterizedTest(name = "titleEveryLevels={0}, level={1} -> {2}")
    @CsvSource({
        "10, 10, true",
        "10, 20, true",
        "10, 15, false",
        "10, 0, false",
        "1, 1, true",
        "1, 5, true",
        "1, 0, false",
        "0, 10, false",
        "-1, 10, false",
        "-5, 0, false",
    })
    @DisplayName("isMilestoneLevel: title-every-levelsの倍数判定(1のとき/0や負値のときを含む)")
    void isMilestoneLevelBoundaries(int titleEveryLevels, int level, boolean expected) {
        assertEquals(expected, SkillExpFeedbackService.isMilestoneLevel(titleEveryLevels, level));
    }

    // --- exp-display.mode 分岐 (bossbar / actionbar) ---------------------------------------------------

    @Test
    @DisplayName("exp-display.mode=actionbar: アクションバーのみ送信し、ボスバーは出さない")
    void actionBarModeSendsActionBarOnly() {
        stubConfig(true, 4.0, false, false, "ENTITY_PLAYER_LEVELUP", 10);
        SkillExpFeedbackService service = newService();

        service.onExpGranted(player.getUniqueId(), SKILL_ID, 5.0, unchangedResult());
        server.getScheduler().performOneTick();

        assertNotNull(player.nextActionBar(), "actionbar mode must send an action bar");
        assertTrue(player.getBossBars().isEmpty(), "actionbar mode must not show a boss bar");
    }

    @Test
    @DisplayName("exp-display.mode=bossbar(既定): ボスバーを表示し、アクションバーは送らない")
    void bossBarModeShowsBossBarOnly() {
        stubConfig(false, 4.0, false, false, "ENTITY_PLAYER_LEVELUP", 10);
        SkillCatalogEntry entry = new SkillCatalogEntry(SKILL_ID, 100, "n/a",
                level -> 100L, Map.of());
        when(catalog.get(SKILL_ID)).thenReturn(entry);
        SkillExpFeedbackService service = newService();

        SkillProgress after = new SkillProgress(3, 25.0, 325.0, 0, 100);
        NativeProgressionService.GrantResult result =
                new NativeProgressionService.GrantResult(SKILL_ID, null, after, 0, 0);
        service.onExpGranted(player.getUniqueId(), SKILL_ID, 5.0, result);
        server.getScheduler().performOneTick();

        assertNull(player.nextActionBar(), "bossbar mode must not send an action bar");
        assertEquals(1, player.getBossBars().size(), "bossbar mode must show exactly one boss bar");
        Iterator<BossBar> it = player.getBossBars().iterator();
        BossBar bar = it.next();
        // curve level->100L に固定しているので span=100、residual=25 -> progress=0.25
        assertEquals(0.25f, bar.progress(), 1e-6f);
    }

    @Test
    @DisplayName("日次逓減が効いていると、EXP表示に ×70% が付く（2026-08-18）")
    void expDisplayCarriesTheDailyRateBadge() {
        // 逓減は 2026-07-31 から動いていたのに、倍率を確認できる場所がどこにも無かった。
        // EXPを稼いだ瞬間に必ず出るのはこの表示だけなので、ここに出さないと気づく機会が無い。
        stubConfig(true, 4.0, false, false, "ENTITY_PLAYER_LEVELUP", 10);
        SkillExpFeedbackService service = new SkillExpFeedbackService(plugin, config, catalog,
                id -> "TestSkill",
                (playerId, skillId) -> new DailyExpDiminishing.Status(0.7, 120_000.0, -1.0, -1.0, 3_600_000.0));

        service.onExpGranted(player.getUniqueId(), SKILL_ID, 5.0, unchangedResult());
        server.getScheduler().performOneTick();

        String actionBar = PlainTextComponentSerializer.plainText().serialize(player.nextActionBar());
        assertTrue(actionBar.contains("×70%"), actionBar);
    }

    @Test
    @DisplayName("逓減が等倍なら何も足さない（常時ノイズにしない）")
    void expDisplayStaysCleanAtFullRate() {
        stubConfig(true, 4.0, false, false, "ENTITY_PLAYER_LEVELUP", 10);
        SkillExpFeedbackService service = new SkillExpFeedbackService(plugin, config, catalog,
                id -> "TestSkill",
                (playerId, skillId) -> new DailyExpDiminishing.Status(1.0, 10.0, 90.0, -1.0, -1.0));

        service.onExpGranted(player.getUniqueId(), SKILL_ID, 5.0, unchangedResult());
        server.getScheduler().performOneTick();

        String actionBar = PlainTextComponentSerializer.plainText().serialize(player.nextActionBar());
        assertTrue(!actionBar.contains("×"), actionBar);
    }

    // --- B3: 複数スキル同時ボスバー / 上限FIFO失効 / ログアウト掃除 ------------------------------------

    private SkillExpFeedbackService newIdentityNamedService() {
        return new SkillExpFeedbackService(plugin, config, catalog, id -> id);
    }

    private NativeProgressionService.GrantResult resultFor(String skillId, double residual) {
        SkillProgress after = new SkillProgress(3, residual, 325.0, 0, 100);
        return new NativeProgressionService.GrantResult(skillId, null, after, 0, 0);
    }

    private void stubCurve(String skillId) {
        SkillCatalogEntry entry = new SkillCatalogEntry(skillId, 100, "n/a", level -> 100L, Map.of());
        when(catalog.get(skillId)).thenReturn(entry);
    }

    private static Set<String> bossBarNames(PlayerMock player) {
        Set<String> names = new java.util.HashSet<>();
        for (BossBar bar : player.getBossBars()) {
            names.add(PlainTextComponentSerializer.plainText().serialize(bar.name()));
        }
        return names;
    }

    @Test
    @DisplayName("B3: 2スキルのEXPを同時に得た場合、両方のボスバーが表示される(後勝ちで消えない)")
    void twoSkillsGrantedSimultaneouslyBothShowIndependentBossBars() {
        stubConfig(false, 4.0, false, false, "ENTITY_PLAYER_LEVELUP", 10);
        when(config.maxConcurrentBossBars()).thenReturn(4);
        stubCurve("SKILL_A");
        stubCurve("SKILL_B");
        SkillExpFeedbackService service = newIdentityNamedService();

        service.onExpGranted(player.getUniqueId(), "SKILL_A", 5.0, resultFor("SKILL_A", 25.0));
        server.getScheduler().performOneTick();
        service.onExpGranted(player.getUniqueId(), "SKILL_B", 5.0, resultFor("SKILL_B", 25.0));
        server.getScheduler().performOneTick();

        assertEquals(2, player.getBossBars().size(),
                "both skills must keep their own boss bar visible, not overwrite each other");
        Set<String> names = bossBarNames(player);
        assertTrue(names.stream().anyMatch(n -> n.contains("SKILL_A")));
        assertTrue(names.stream().anyMatch(n -> n.contains("SKILL_B")));
    }

    @Test
    @DisplayName("B3: 更新中の同一スキルは1本のまま(重複しない)")
    void sameSkillGrantedTwiceUpdatesInPlaceWithoutDuplicating() {
        stubConfig(false, 4.0, false, false, "ENTITY_PLAYER_LEVELUP", 10);
        stubCurve("SKILL_A");
        SkillExpFeedbackService service = newIdentityNamedService();

        service.onExpGranted(player.getUniqueId(), "SKILL_A", 5.0, resultFor("SKILL_A", 25.0));
        server.getScheduler().performOneTick();
        service.onExpGranted(player.getUniqueId(), "SKILL_A", 5.0, resultFor("SKILL_A", 30.0));
        server.getScheduler().performOneTick();

        assertEquals(1, player.getBossBars().size(), "the same skill must update its existing bar, not add a new one");
    }

    @Test
    @DisplayName("B3: 同時表示数の上限を超えたら、最も古く追加されたスキルのボスバーから閉じる(FIFO)")
    void exceedingCapacityEvictsOldestSkillFirst() {
        stubConfig(false, 4.0, false, false, "ENTITY_PLAYER_LEVELUP", 10);
        when(config.maxConcurrentBossBars()).thenReturn(2);
        stubCurve("SKILL_A");
        stubCurve("SKILL_B");
        stubCurve("SKILL_C");
        SkillExpFeedbackService service = newIdentityNamedService();

        service.onExpGranted(player.getUniqueId(), "SKILL_A", 5.0, resultFor("SKILL_A", 25.0));
        server.getScheduler().performOneTick();
        service.onExpGranted(player.getUniqueId(), "SKILL_B", 5.0, resultFor("SKILL_B", 25.0));
        server.getScheduler().performOneTick();
        service.onExpGranted(player.getUniqueId(), "SKILL_C", 5.0, resultFor("SKILL_C", 25.0));
        server.getScheduler().performOneTick();

        assertEquals(2, player.getBossBars().size(), "the cap (2) must never be exceeded");
        Set<String> names = bossBarNames(player);
        assertFalse(names.stream().anyMatch(n -> n.contains("SKILL_A")),
                "the oldest skill (A) must have been evicted first");
        assertTrue(names.stream().anyMatch(n -> n.contains("SKILL_B")));
        assertTrue(names.stream().anyMatch(n -> n.contains("SKILL_C")));
    }

    @Test
    @DisplayName("B3: ログアウト時、そのプレイヤーの全ボスバーが破棄される")
    void playerQuitClearsAllBossBarsForThatPlayer() {
        stubConfig(false, 4.0, false, false, "ENTITY_PLAYER_LEVELUP", 10);
        when(config.maxConcurrentBossBars()).thenReturn(4);
        stubCurve("SKILL_A");
        stubCurve("SKILL_B");
        SkillExpFeedbackService service = newIdentityNamedService();

        service.onExpGranted(player.getUniqueId(), "SKILL_A", 5.0, resultFor("SKILL_A", 25.0));
        server.getScheduler().performOneTick();
        service.onExpGranted(player.getUniqueId(), "SKILL_B", 5.0, resultFor("SKILL_B", 25.0));
        server.getScheduler().performOneTick();
        assertEquals(2, player.getBossBars().size(), "sanity check: both bars are up before quitting");

        service.onQuit(new PlayerQuitEvent(player, "bye"));

        assertTrue(player.getBossBars().isEmpty(), "logging out must clear every boss bar for that player");
    }

    // --- レベルアップ通知 (S6): チャット / サウンド ----------------------------------------------------

    @Test
    @DisplayName("レベルアップ時: levelUpChat=true でチャット通知、levelUpSoundEnabled=true でサウンド再生")
    void levelUpSendsChatAndSound() {
        stubConfig(true, 4.0, true, true, "ENTITY_PLAYER_LEVELUP", 10);
        SkillExpFeedbackService service = newService();

        SkillProgress after = new SkillProgress(10, 0.0, 1000.0, 0, 100);
        NativeProgressionService.GrantResult result =
                new NativeProgressionService.GrantResult(SKILL_ID, null, after, 1, 0);
        service.onExpGranted(player.getUniqueId(), SKILL_ID, 50.0, result);
        server.getScheduler().performOneTick();

        String message = player.nextMessage();
        assertNotNull(message, "level up must send a chat message when levelUpChat=true");
        assertTrue(message.contains("Lv10"), "chat message should mention the new level: " + message);
        assertTrue(!player.getHeardSounds().isEmpty(), "level up must play a sound when levelUpSoundEnabled=true");
    }

    @Test
    @DisplayName("レベルアップ時: levelUpChat=false / levelUpSoundEnabled=false ならどちらも発生しない")
    void levelUpSuppressesChatAndSoundWhenDisabled() {
        stubConfig(true, 4.0, false, false, "ENTITY_PLAYER_LEVELUP", 10);
        SkillExpFeedbackService service = newService();

        SkillProgress after = new SkillProgress(10, 0.0, 1000.0, 0, 100);
        NativeProgressionService.GrantResult result =
                new NativeProgressionService.GrantResult(SKILL_ID, null, after, 1, 0);
        service.onExpGranted(player.getUniqueId(), SKILL_ID, 50.0, result);
        server.getScheduler().performOneTick();

        assertNull(player.nextMessage(), "levelUpChat=false must not send a chat message");
        assertTrue(player.getHeardSounds().isEmpty(), "levelUpSoundEnabled=false must not play a sound");
    }

    @Test
    @DisplayName("levelsChanged<=0(レベルアップなし): チャット/サウンドは発生しない")
    void noLevelUpWhenLevelsChangedIsZero() {
        stubConfig(true, 4.0, true, true, "ENTITY_PLAYER_LEVELUP", 10);
        SkillExpFeedbackService service = newService();

        service.onExpGranted(player.getUniqueId(), SKILL_ID, 5.0, unchangedResult());
        server.getScheduler().performOneTick();

        assertNull(player.nextMessage(), "no level up -> no chat message");
        assertTrue(player.getHeardSounds().isEmpty(), "no level up -> no sound");
    }

    // --- ActionBarRouter 連携（2026-09 機構4） -----------------------------------------------------------

    @Test
    @DisplayName("ルータ設定時: expDisplayActionBarOnly=true でルータ経由に送られる（sinkに届く）")
    void routesThroughActionBarRouterWhenSet() {
        stubConfig(true, 4.0, false, false, "ENTITY_PLAYER_LEVELUP", 10);
        SkillExpFeedbackService service = newService();
        List<Component> sunk = new ArrayList<>();
        ActionBarRouter router = new ActionBarRouter(() -> 0L, (p, c) -> sunk.add(c));
        service.setActionBarRouter(router);

        service.onExpGranted(player.getUniqueId(), SKILL_ID, 5.0, unchangedResult());
        server.getScheduler().performOneTick();

        assertEquals(1, sunk.size(), "expected the EXP line to reach the router's sink");
        assertNull(player.nextActionBar(), "must not also call sendActionBar directly when routed");
    }

    @Test
    @DisplayName("ルータ設定時: 予告が走っている viewer には届かない")
    void routerSuppressesExpWhileTelegraphIsActive() {
        stubConfig(true, 4.0, false, false, "ENTITY_PLAYER_LEVELUP", 10);
        SkillExpFeedbackService service = newService();
        List<Component> sunk = new ArrayList<>();
        ActionBarRouter router = new ActionBarRouter(() -> 0L, (p, c) -> sunk.add(c));
        router.telegraphUpdate(player, "boss:slam", false, 1000L, Component.text("予告"));
        sunk.clear();
        service.setActionBarRouter(router);

        service.onExpGranted(player.getUniqueId(), SKILL_ID, 5.0, unchangedResult());
        server.getScheduler().performOneTick();

        assertTrue(sunk.isEmpty(), "EXP line must be dropped while a telegraph is active for that viewer");
        assertNull(player.nextActionBar());
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private SkillExpFeedbackService newService() {
        return new SkillExpFeedbackService(plugin, config, catalog, id -> "TestSkill");
    }

    private NativeProgressionService.GrantResult unchangedResult() {
        SkillProgress after = new SkillProgress(0, 5.0, 5.0, 0, 100);
        return new NativeProgressionService.GrantResult(SKILL_ID, null, after, 0, 0);
    }

    private void stubConfig(boolean actionBarOnly, double barSeconds, boolean levelUpChat,
                            boolean levelUpSoundEnabled, String levelUpSound, int titleEveryLevels) {
        when(config.expDisplayActionBarOnly()).thenReturn(actionBarOnly);
        when(config.expBarSeconds()).thenReturn(barSeconds);
        when(config.levelUpChat()).thenReturn(levelUpChat);
        when(config.levelUpSoundEnabled()).thenReturn(levelUpSoundEnabled);
        when(config.levelUpSound()).thenReturn(levelUpSound);
        when(config.titleEveryLevels()).thenReturn(titleEveryLevels);
    }
}
