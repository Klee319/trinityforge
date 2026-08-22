package com.trinityforge.progression;

import com.trinityforge.progression.infrastructure.sqlite.DailyExpWindowStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日次EXP逓減の蓄積が<b>退出／再起動をまたいで残る</b>ことを固定する（2026-08-18）。
 *
 * <p>ここが壊れると機構ごと無効になる ── 退出で蓄積が消えるなら、逓減が効き始めた瞬間に
 * 入り直すだけで等倍へ戻せてしまい、「1日の稼ぎ総量を薄める」という目的を1ミリも達成しない。
 * 2026-07-31 の導入から 2026-08-18 まで、実際にその状態で動いていた。
 *
 * <p>時計は {@link DailyExpDiminishing} と {@link DailyExpWindowStore} で<b>同じものを共有</b>する。
 * 別々にすると保存の瞬間に実時刻ぶんの減衰が掛かってしまい、検証が成立しない。
 */
class DailyExpWindowPersistenceTest {

    private static final double HOUR = 3_600_000.0;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private DailyExpWindowStore store;

    private static DailyExpDiminishing.Settings settings() {
        // 1000 稼ぐごとに 0.5 倍(離散) → 下限 0.25
        return new DailyExpDiminishing.Settings(true, 24 * HOUR, 1000.0, 0.5, 0.25, Set.of());
    }

    @BeforeEach
    void openStore() throws SQLException {
        store = new DailyExpWindowStore("jdbc:sqlite::memory:", now::get);
    }

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    private DailyExpWindowPersistence persistence(DailyExpDiminishing daily) {
        return new DailyExpWindowPersistence(daily, store, DailyExpWindowPersistenceTest::settings,
                message -> { throw new AssertionError("永続化が失敗した: " + message); });
    }

    @Test
    @DisplayName("退出→再ログインで逓減が等倍へ戻らない（これが直らないと機構ごと無意味）")
    void diminishingSurvivesRelogin() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();

        // 2段ぶん稼いで 0.25 まで落とす
        daily.consume(settings(), player, "MINING", 3000.0);
        assertEquals(0.25, daily.status(settings(), player, "MINING").multiplier(), 1e-9);

        // ログアウト（保存してから捨てる）
        persistence.saveAndForget(player);
        assertEquals(0, daily.trackedPlayers(), "退出でメモリは解放されること");
        assertEquals(1.0, daily.status(settings(), player, "MINING").multiplier(), 1e-9);

        // 即座に再ログイン
        persistence.load(player);
        assertEquals(0.25, daily.status(settings(), player, "MINING").multiplier(), 1e-9,
                "入り直しただけで等倍に戻ってはいけない");
    }

    @Test
    @DisplayName("オフラインの時間ぶんはちゃんと回復する（永続化＝回復しないではない）")
    void offlineTimeStillDecays() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();

        daily.consume(settings(), player, "MINING", 3000.0);
        persistence.saveAndForget(player);

        // 24時間(=時定数)オフライン → 蓄積は 1/e ≒ 36.8% まで落ちる
        now.addAndGet((long) (24 * HOUR));
        persistence.load(player);

        double restored = daily.accumulated(settings(), player, "MINING");
        assertEquals(3000.0 / Math.E, restored, 1.0);
        // 1103 ≒ floor(1103/1000)=1段 → 0.5 まで回復している
        assertEquals(0.5, daily.status(settings(), player, "MINING").multiplier(), 1e-9);
    }

    @Test
    @DisplayName("復元直後の付与で偽の「取得量が下がりました」を出さない")
    void restoreAlsoRestoresTheLastMultiplier() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();

        daily.consume(settings(), player, "MINING", 3000.0);
        persistence.saveAndForget(player);
        persistence.load(player);

        DailyExpDiminishing.Applied applied =
                daily.consumeDetailed(settings(), player, "MINING", 1.0);
        assertEquals(0.25, applied.multiplier(), 1e-9);
        assertFalse(applied.worsened(),
                "ログアウト前から下がっていただけなので「下がりました」は誤報");
        assertFalse(applied.improved());
    }

    @Test
    @DisplayName("サーバを往復しても蓄積が消えない（古い値の上書きで後退させない）")
    void staleWriteDoesNotOverwriteNewerAccumulation() throws SQLException {
        UUID player = UUID.randomUUID();

        // 移動元サーバ: 3000 ぶん稼いだ状態でサーバ移動する
        DailyExpDiminishing origin = new DailyExpDiminishing(now::get);
        origin.consume(settings(), player, "MINING", 3000.0);

        // 移動先サーバ: join は移動元の quit より先に起きるので、DB がまだ空のまま読む
        DailyExpDiminishing destination = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence destinationSide = persistence(destination);
        destinationSide.load(player);
        destination.consume(settings(), player, "MINING", 200.0);

        // 移動元サーバの quit 保存があとから届く（こちらが本当の蓄積）
        persistence(origin).saveAndForget(player);

        // 移動先サーバの定期保存が、古い前提の小さい値で上書きしようとする
        destinationSide.save(player);

        List<DailyExpDiminishing.WindowSnapshot> rows = store.load(player);
        assertEquals(1, rows.size());
        assertEquals(3000.0, rows.get(0).amount(), 1e-6,
                "往復しただけで逓減が消えてはいけない");
    }

    @Test
    @DisplayName("保存より先に忘れると何も残らない（順序が契約であることの固定）")
    void forgetBeforeSaveLosesEverything() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();

        daily.consume(settings(), player, "MINING", 3000.0);
        daily.forget(player);
        persistence.save(player);

        persistence.load(player);
        assertEquals(1.0, daily.status(settings(), player, "MINING").multiplier(), 1e-9);
    }

    @Test
    @DisplayName("減衰しきった行は表から消える（無限に太らせない）")
    void negligibleRowsArePruned() throws SQLException {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();

        daily.consume(settings(), player, "MINING", 3000.0);
        persistence.saveAndForget(player);
        assertFalse(store.load(player).isEmpty());

        // 10日放置 → exp(-10) ≒ 0.0000454 倍 = 0.14 で足切り未満
        now.addAndGet((long) (240 * HOUR));
        persistence.load(player);
        persistence.save(player);
        assertTrue(store.load(player).isEmpty());
    }

    @Test
    @DisplayName("スキルごとに独立して保存される")
    void perSkillRowsAreIndependent() throws SQLException {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();

        daily.consume(settings(), player, "MINING", 3000.0);
        daily.consume(settings(), player, "WOODCUTTING", 1500.0);
        persistence.saveAndForget(player);
        persistence.load(player);

        assertEquals(0.25, daily.status(settings(), player, "MINING").multiplier(), 1e-9);
        assertEquals(0.5, daily.status(settings(), player, "WOODCUTTING").multiplier(), 1e-9);
        assertEquals(2, store.load(player).size());
    }

    @Test
    @DisplayName("進行リセットで蓄積も消える")
    void deleteClearsStoredWindows() throws SQLException {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();

        daily.consume(settings(), player, "MINING", 3000.0);
        persistence.saveAndForget(player);
        store.delete(player);

        persistence.load(player);
        assertEquals(1.0, daily.status(settings(), player, "MINING").multiplier(), 1e-9);
    }

    // ------------------------------------------------------------------
    // 2026-08-19 / W-154: 24時間での強制解除と、全員ぶんの一括解除
    // ------------------------------------------------------------------

    /** 発動から24時間で強制解除する設定。 */
    private static DailyExpDiminishing.Settings settingsWithRelease() {
        return new DailyExpDiminishing.Settings(true, 24 * HOUR, 1000.0, 0.5, 0.25, Set.of(), 24 * HOUR);
    }

    @Test
    @DisplayName("W-154: 逓減の発動時刻も永続化される（保存→復元で期限が延びない）")
    void theLockTimestampSurvivesASaveAndLoad() throws SQLException {
        UUID player = UUID.randomUUID();
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        long lockedAt = now.get();
        daily.consume(settingsWithRelease(), player, "MINING", 50_000.0);

        store.save(player, daily.snapshot(player), settingsWithRelease().windowMillis());
        List<DailyExpDiminishing.WindowSnapshot> loaded = store.load(player);

        assertEquals(1, loaded.size());
        assertEquals(lockedAt, loaded.get(0).lockedAtMillis(),
                "発動時刻を保存していないと、入り直すたびに期限が後ろへずれて24時間経っても解除されない");

        // 24時間後にログインし直した体で復元 → 期限切れなので何も戻らない（＝等倍）。
        now.set(lockedAt + (long) (24 * HOUR) + 1L);
        DailyExpDiminishing fresh = new DailyExpDiminishing(now::get);
        fresh.restore(settingsWithRelease(), player, loaded);
        assertEquals(1.0, fresh.status(settingsWithRelease(), player, "MINING").multiplier(), 1e-9);
    }

    /** 期限つき設定を使う {@link DailyExpWindowPersistence}。 */
    private DailyExpWindowPersistence persistenceWithRelease(DailyExpDiminishing daily) {
        return new DailyExpWindowPersistence(daily, store,
                DailyExpWindowPersistenceTest::settingsWithRelease,
                message -> { throw new AssertionError("永続化が失敗した: " + message); });
    }

    @Test
    @DisplayName("期限切れの行が残っていても、次の逓減は入り直しで消えない（2026-08-22 実サーバ報告）")
    void anExpiredRowDoesNotDragTheNextLockBackInTime() {
        UUID player = UUID.randomUUID();
        DailyExpDiminishing.Settings s = settingsWithRelease();

        // 1日目: 逓減が発動した状態でログアウトする。
        DailyExpDiminishing day1 = new DailyExpDiminishing(now::get);
        day1.consume(s, player, "MINING", 50_000.0);
        persistenceWithRelease(day1).saveAndForget(player);

        // 48時間後にログイン。期限(24h)を過ぎているので解除されて等倍で始まる。
        now.addAndGet((long) (48 * HOUR));
        DailyExpDiminishing day2 = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence day2Side = persistenceWithRelease(day2);
        day2Side.load(player);
        assertEquals(1.0, day2.status(s, player, "MINING").multiplier(), 1e-9,
                "24時間を過ぎた逓減は解除されていること");

        // 同じセッションでまた稼いで逓減を発動させ、ログアウトする。
        day2.consume(s, player, "MINING", 50_000.0);
        assertTrue(day2.status(s, player, "MINING").multiplier() < 1.0);
        day2Side.saveAndForget(player);

        // 1時間後に入り直す。ここで等倍へ戻るなら、古い発動時刻に引き戻されている。
        now.addAndGet((long) HOUR);
        DailyExpDiminishing day3 = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence day3Side = persistenceWithRelease(day3);
        day3Side.load(player);
        assertTrue(day3.status(s, player, "MINING").multiplier() < 1.0,
                "入り直しただけで逓減が消えてはいけない（期限切れの古い行が新しい発動時刻を"
                        + "引き戻していた実サーバの不具合）");
    }

    @Test
    @DisplayName("ログイン時に期限切れの行を物理削除する（読み飛ばすだけだと蘇る）")
    void loadPurgesReleasedRows() throws SQLException {
        UUID player = UUID.randomUUID();
        DailyExpDiminishing.Settings s = settingsWithRelease();

        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        daily.consume(s, player, "MINING", 50_000.0);
        persistenceWithRelease(daily).saveAndForget(player);
        assertFalse(store.load(player).isEmpty());

        now.addAndGet((long) (25 * HOUR));
        persistenceWithRelease(new DailyExpDiminishing(now::get)).load(player);
        assertTrue(store.load(player).isEmpty(),
                "期限切れの行を残すと、あとの save が max() で古い蓄積を蘇らせる");
    }

    @Test
    @DisplayName("期限内の行はログインで消さない（掃除しすぎない）")
    void loadKeepsRowsThatAreStillWithinTheDeadline() throws SQLException {
        UUID player = UUID.randomUUID();
        DailyExpDiminishing.Settings s = settingsWithRelease();

        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        daily.consume(s, player, "MINING", 50_000.0);
        persistenceWithRelease(daily).saveAndForget(player);

        now.addAndGet((long) (23 * HOUR));
        DailyExpDiminishing fresh = new DailyExpDiminishing(now::get);
        persistenceWithRelease(fresh).load(player);
        assertFalse(store.load(player).isEmpty(), "期限内の行まで消してはいけない");
        assertTrue(fresh.status(s, player, "MINING").multiplier() < 1.0);
    }

    @Test
    @DisplayName("W-154: reset-id を変えたときだけ全員ぶんが1回消える（再起動で2度は消えない）")
    void theResetTokenClearsEveryoneExactlyOnce() throws SQLException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        daily.consume(settings(), a, "MINING", 50_000.0);
        daily.consume(settings(), b, "WOODCUTTING", 50_000.0);
        store.save(a, daily.snapshot(a), settings().windowMillis());
        store.save(b, daily.snapshot(b), settings().windowMillis());
        assertFalse(store.load(a).isEmpty());
        assertFalse(store.load(b).isEmpty());

        assertEquals(2, store.applyResetIfRequested("2026-08-19-w154"), "2人ぶん消えること");
        assertTrue(store.load(a).isEmpty());
        assertTrue(store.load(b).isEmpty());

        // 同じ合言葉での再起動では消さない（消し続けると逓減が永久に効かなくなる）。
        daily.consume(settings(), a, "MINING", 50_000.0);
        store.save(a, daily.snapshot(a), settings().windowMillis());
        assertEquals(-1, store.applyResetIfRequested("2026-08-19-w154"));
        assertFalse(store.load(a).isEmpty(), "同じ reset-id では2度目は消さないこと");

        // 別の合言葉にすればもう一度消える。
        assertEquals(1, store.applyResetIfRequested("2026-09-01-again"));
        assertTrue(store.load(a).isEmpty());
    }

    @Test
    @DisplayName("W-154: 空の reset-id は何もしない（既定で勝手に消さない）")
    void anEmptyResetTokenIsANoOp() throws SQLException {
        UUID player = UUID.randomUUID();
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        daily.consume(settings(), player, "MINING", 50_000.0);
        store.save(player, daily.snapshot(player), settings().windowMillis());

        assertEquals(-1, store.applyResetIfRequested(""));
        assertEquals(-1, store.applyResetIfRequested(null));
        assertFalse(store.load(player).isEmpty());
    }

    @Test
    @DisplayName("W-154: locked_at 列が無い既存DBでも開ける（CREATE TABLE IF NOT EXISTS は列を足さない）")
    void anOlderDatabaseWithoutTheLockColumnIsMigrated() throws SQLException {
        // 旧スキーマ（locked_at 無し）のDBを直接作る。実サーバのDBはこの形なので、
        // ここを通らないと「新品の環境だけ動く」修正になる。
        java.io.File file;
        try {
            file = java.io.File.createTempFile("tf-daily-exp-migration", ".db");
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
        file.deleteOnExit();
        String url = "jdbc:sqlite:" + file.getAbsolutePath();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url);
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE daily_exp_window ("
                    + "player_uuid TEXT NOT NULL, skill_id TEXT NOT NULL,"
                    + " amount REAL NOT NULL, updated_at INTEGER NOT NULL,"
                    + " PRIMARY KEY (player_uuid, skill_id))");
            st.executeUpdate("INSERT INTO daily_exp_window VALUES ('"
                    + UUID.nameUUIDFromBytes("old".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    + "', 'MINING', 12345.0, 1000000)");
        }

        try (DailyExpWindowStore migrated = new DailyExpWindowStore(url, now::get)) {
            List<DailyExpDiminishing.WindowSnapshot> rows = migrated.load(
                    UUID.nameUUIDFromBytes("old".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(1, rows.size(), "旧DBの行が読めなくなっている");
            assertEquals(12345.0, rows.get(0).amount(), 1e-9);
            assertEquals(0L, rows.get(0).lockedAtMillis(), "既存行は未発動(0)として読むこと");
        }
    }
}
