package com.trinityforge.progression.event;

import com.trinityforge.progression.DailyExpDiminishing;
import com.trinityforge.progression.ExpDiminishingCurve;
import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.PlayerLockRegistry;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日次逓減（直近24時間の稼ぎでEXP取得量が薄まる仕組み）を<b>プレイヤーへ知らせる</b>経路の契約。
 *
 * <h2>なぜ要るのか</h2>
 * 逓減自体は 2026-07-31 から動いていたが、<b>通知も表示も1つも実装されていなかった</b>。
 * 倍率を離散の段にした設計意図が「あと何EXPで落ちるのか・何をすれば戻るのかを数えられること」
 * だったのに、プレイヤーからは「なんとなくEXPが渋い」としか分からない状態だった
 * （{@code untilNextStep()} は書いてあるのに production から一度も呼ばれていなかった）。
 *
 * <h2>固定していること</h2>
 * <ol>
 *   <li>進行サービスが<b>段が動いたときだけ</b>受け口へ流す（毎回流すとチャットが埋まる）</li>
 *   <li>非同期スレッドから呼ばれてもメインスレッドへ寄せてから送る
 *       ── EXP付与は {@code NativeExperienceDispatcher} の非同期タスクから走るので、
 *       ここを外すと本番でだけ落ちる</li>
 *   <li>本文に「今いくつか」だけでなく「戻るまでの目安」が入る</li>
 * </ol>
 */
class DailyExpRateNotificationTest {

    private static final double HOUR = 3_600_000.0;

    private ServerMock server;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("DailyExpRateTest");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 100 稼ぐごとに 0.5 倍（離散）、下限 0.25。1回の付与で段をまたげる大きさにしてある。 */
    private static DailyExpDiminishing.Settings settings() {
        return new DailyExpDiminishing.Settings(true, 24 * HOUR, 100.0, 0.5, 0.25, Set.of());
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("進行サービスは段が動いたときだけ通知する（同じ段のあいだは黙る）")
    void serviceNotifiesOnlyWhenTheStepMoves() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        AtomicLong clock = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(clock::get);
        List<String> notified = new ArrayList<>();

        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService service = new NativeProgressionService(
                    repository, catalog, id -> 0.0, new PlayerLockRegistry(),
                    ExpDiminishingCurve.NONE, daily, DailyExpRateNotificationTest::settings);
            service.setDailyExpRateSink((playerId, skillId, applied) ->
                    notified.add(skillId + " " + applied.previousMultiplier() + "->" + applied.multiplier()));
            UUID player = UUID.randomUUID();

            service.grantExp(player, SkillId.MINING, 60.0);
            assertTrue(notified.isEmpty(), "1段目に届く前に通知するとノイズになる");

            service.grantExp(player, SkillId.MINING, 60.0);   // 蓄積 120 -> 1段
            assertEquals(List.of("MINING 1.0->0.5"), notified, "段が落ちた瞬間に1回だけ通知する");

            notified.clear();
            service.grantExp(player, SkillId.MINING, 10.0);   // 蓄積 130 -> 同じ段
            assertTrue(notified.isEmpty(), "同じ段のあいだは黙る（毎回流すと読まれなくなる）");

            // 時間経過で戻ったことも知らせる（戻ったのに気づけないと、何をすれば戻るのか学習できない）。
            clock.addAndGet((long) (48 * HOUR));
            service.grantExp(player, SkillId.MINING, 1.0);
            assertEquals(List.of("MINING 0.5->1.0"), notified);
        }
    }

    @Test
    @DisplayName("逓減が無効なら一度も通知しない（config を書くまで挙動が変わらない）")
    void disabledNeverNotifies() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        List<String> notified = new ArrayList<>();

        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService service = new NativeProgressionService(
                    repository, catalog, id -> 0.0, new PlayerLockRegistry(),
                    ExpDiminishingCurve.NONE, new DailyExpDiminishing(() -> 0L),
                    () -> DailyExpDiminishing.Settings.DISABLED);
            service.setDailyExpRateSink((playerId, skillId, applied) -> notified.add(skillId));
            UUID player = UUID.randomUUID();

            for (int i = 0; i < 20; i++) {
                service.grantExp(player, SkillId.MINING, 1000.0);
            }

            assertTrue(notified.isEmpty());
            assertNull(service.dailyExpRateStatus(player, SkillId.MINING),
                       "無効なら表示側にも出さない");
        }
    }

    @Test
    @DisplayName("段が落ちた通知には、今の倍率と「戻るまでの目安」が入る")
    void loweredMessageCarriesRateAndRecovery() {
        DailyExpDiminishing.Status status = DailyExpDiminishing.statusOf(settings(), 150.0);
        String text = plain(BukkitDailyExpRateNotifier.loweredMessage("採掘", 0.5, status));

        assertTrue(text.contains("採掘"), text);
        assertTrue(text.contains("50%"), text);
        assertTrue(text.contains("等倍まで"), "何をすればどれくらいで戻るのかが無いと率だけ見せても意味が無い: " + text);
        assertTrue(plain(BukkitDailyExpRateNotifier.recoveredMessage("採掘", 1.0)).contains("100%"));
    }

    @Test
    @DisplayName("非同期スレッドから呼ばれてもメインスレッドへ寄せてから送る")
    void asyncCallIsDeferredToTheMainThread() throws Exception {
        PlayerMock player = server.addPlayer();
        BukkitDailyExpRateNotifier notifier = new BukkitDailyExpRateNotifier(
                plugin, id -> "採掘", (id, skill) -> DailyExpDiminishing.statusOf(settings(), 150.0));
        DailyExpDiminishing.Applied dropped = new DailyExpDiminishing.Applied(0.5, 1.0, 150.0);

        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            notifier.onDailyExpRateChanged(player.getUniqueId(), "MINING", dropped);
            done.countDown();
        });
        worker.start();
        assertTrue(done.await(5, TimeUnit.SECONDS), "非同期呼び出しが例外なく戻ること");
        worker.join();

        assertNull(player.nextMessage(), "非同期の時点では送っていない（送ると本番で例外）");

        server.getScheduler().performOneTick();

        String message = player.nextMessage();
        assertNotNull(message, "次の tick で届く");
        assertTrue(message.contains("50%"), message);
    }

    @Test
    @DisplayName("オフラインのプレイヤーには送らない（例外も投げない）")
    void offlinePlayerIsSkipped() {
        BukkitDailyExpRateNotifier notifier = new BukkitDailyExpRateNotifier(
                plugin, id -> "採掘", (id, skill) -> null);

        notifier.onDailyExpRateChanged(UUID.randomUUID(), "MINING",
                new DailyExpDiminishing.Applied(0.5, 1.0, 150.0));

        server.getScheduler().performOneTick();
    }
}
