package com.trinityforge.progression.event;

import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.XpTransitionService;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TrinitySkillLevelUpEvent} の発火契約（外部プラグイン向け公開 API）。
 *
 * <p>固定しているのは 3 点。
 * <ol>
 *   <li><b>到達したレベルごとに 1 回</b>発火する。まとめて 1 回にすると
 *       「Lv10 の節目でアナウンス」を書く側が範囲跨ぎを自前で処理する羽目になる。</li>
 *   <li><b>非同期スレッドから呼ばれてもメインスレッドへ寄せてから</b>発火する。
 *       TF の EXP 付与は {@code NativeExperienceDispatcher} の非同期タスクから走るので、
 *       ここを外すと本番でだけ {@code IllegalStateException} が出る。</li>
 *   <li><b>進行サービスが通知を出す</b>（配線が生きている）。</li>
 * </ol>
 */
class SkillLevelUpEventTest {

    private ServerMock server;
    private Plugin plugin;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("LevelUpEventTest");
        listener = new RecordingListener();
        server.getPluginManager().registerEvents(listener, plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("複数レベル同時上昇は、到達したレベルごとに 1 回ずつ発火する")
    void multiLevelJumpFiresOncePerReachedLevel() {
        PlayerMock player = server.addPlayer();
        BukkitSkillLevelUpDispatcher dispatcher = new BukkitSkillLevelUpDispatcher(plugin);

        dispatcher.onSkillLevelUp(player.getUniqueId(), "MINING", 9, 12);

        assertEquals(3, listener.events.size());
        assertEquals(List.of("MINING 9->10", "MINING 10->11", "MINING 11->12"), listener.describe());
        assertSame(player, listener.events.get(0).getPlayer());
    }

    @Test
    @DisplayName("レベルが上がっていなければ発火しない")
    void noEventWhenLevelDidNotRise() {
        PlayerMock player = server.addPlayer();
        BukkitSkillLevelUpDispatcher dispatcher = new BukkitSkillLevelUpDispatcher(plugin);

        dispatcher.onSkillLevelUp(player.getUniqueId(), "MINING", 12, 12);
        dispatcher.onSkillLevelUp(player.getUniqueId(), "MINING", 12, 3);

        assertTrue(listener.events.isEmpty());
    }

    @Test
    @DisplayName("このサーバにいないプレイヤーでは発火しない（イベントは Player 必須）")
    void noEventForOfflinePlayer() {
        BukkitSkillLevelUpDispatcher dispatcher = new BukkitSkillLevelUpDispatcher(plugin);

        dispatcher.onSkillLevelUp(UUID.randomUUID(), "MINING", 0, 1);

        assertTrue(listener.events.isEmpty());
    }

    @Test
    @DisplayName("非同期スレッドから呼ばれてもメインスレッドへ寄せてから発火する")
    void asyncCallIsDeferredToTheMainThread() throws Exception {
        PlayerMock player = server.addPlayer();
        BukkitSkillLevelUpDispatcher dispatcher = new BukkitSkillLevelUpDispatcher(plugin);

        CountDownLatch scheduled = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            dispatcher.onSkillLevelUp(player.getUniqueId(), "MINING", 0, 1);
            scheduled.countDown();
        });
        worker.start();
        assertTrue(scheduled.await(5, TimeUnit.SECONDS), "非同期呼び出しが例外なく戻ること");
        worker.join();

        assertTrue(listener.events.isEmpty(), "非同期の時点では発火していない（発火すると本番で例外）");

        server.getScheduler().performOneTick();

        assertEquals(List.of("MINING 0->1"), listener.describe(), "次の tick で発火する");
    }

    @Test
    @DisplayName("NativeProgressionService は永続化後にレベル上昇を通知する")
    void progressionServiceNotifiesSinkAfterPersisting() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        List<String> notified = new ArrayList<>();
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService service = new NativeProgressionService(repository, catalog);
            service.setLevelUpSink((playerId, skillId, oldLevel, newLevel) ->
                    notified.add(skillId + " " + oldLevel + "->" + newLevel));
            UUID player = UUID.randomUUID();

            double threeLevels = new XpTransitionService(catalog.get(SkillId.MINING).curve())
                    .cumulativeExpForLevel(3);
            service.grantExp(player, SkillId.MINING, threeLevels);

            assertTrue(notified.contains("MINING 0->3"),
                    "1 回の付与ぶんはまとめて通知され、分解は受け手側の責務: " + notified);
            assertEquals(3, service.progress(player, SkillId.MINING).orElseThrow().level(),
                    "通知時点で DB は既に新しいレベル");

            // レベルが上がらない付与では通知しない。
            notified.clear();
            service.grantExp(player, SkillId.MINING, 0.001);
            assertTrue(notified.isEmpty(), "レベルが動かない付与で通知が出てはいけない: " + notified);
        }
    }

    @Test
    @DisplayName("受け口が例外を投げても EXP 付与は成功したままになる")
    void sinkFailureNeverBreaksExpGrant() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService service = new NativeProgressionService(repository, catalog);
            service.setLevelUpSink((playerId, skillId, oldLevel, newLevel) -> {
                throw new IllegalStateException("受け手が壊れている");
            });
            UUID player = UUID.randomUUID();

            double threeLevels = new XpTransitionService(catalog.get(SkillId.MINING).curve())
                    .cumulativeExpForLevel(3);
            service.grantExp(player, SkillId.MINING, threeLevels);

            assertEquals(3, service.progress(player, SkillId.MINING).orElseThrow().level());
        }
    }

    @Test
    @DisplayName("TrinitySkillLevelUpEvent はキャンセルできない（通知専用）")
    void eventIsNotCancellable() {
        assertFalse(org.bukkit.event.Cancellable.class.isAssignableFrom(TrinitySkillLevelUpEvent.class),
                "キャンセルを許すと『止めても DB は戻らない』誤解を生む");
    }

    private static final class RecordingListener implements Listener {
        private final List<TrinitySkillLevelUpEvent> events = new ArrayList<>();

        @EventHandler
        public void onLevelUp(TrinitySkillLevelUpEvent event) {
            events.add(event);
        }

        List<String> describe() {
            List<String> out = new ArrayList<>();
            for (TrinitySkillLevelUpEvent e : events) {
                out.add(e.getSkillId() + " " + e.getOldLevel() + "->" + e.getNewLevel());
            }
            return out;
        }
    }
}
