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
}
