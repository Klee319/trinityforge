package com.trinityforge.progression;

import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W-314 タスク2の回帰テスト。{@link ProgressionCurveReconciler#recalculatePlayer} は
 * {@code repository.saveSkillProgress}/{@code savePointBalance} を絶対値で上書きするため、
 * ロックを取らずに実行すると reload と同時刻のEXP付与がロストアップデートになる。
 *
 * <p>修正後は {@link PlayerLockRegistry#withLock} でプレイヤー単位に直列化される。ここでは
 * 実際に EXP 付与と競合させる代わりに、<b>同じ共有ロックを外から掴んだ状態</b>で
 * {@code recalculatePlayer} がブロックされ、解放後にだけ進行することを確認する
 * (ブラウザ資料の指示どおり「短いタイムアウトで確認する」方式)。
 */
class ProgressionCurveReconcilerLockingTest {

    @Test
    @DisplayName("同じプレイヤーの共有ロックを外部が保持している間、recalculatePlayerはブロックされる")
    void recalculatePlayerBlocksWhileSharedLockIsHeldForSamePlayer() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            UUID player = UUID.randomUUID();
            PlayerLockRegistry sharedLocks = new PlayerLockRegistry();
            ProgressionCurveReconciler reconciler =
                    new ProgressionCurveReconciler(repository, catalog, sharedLocks, () -> 1);

            CountDownLatch holderEntered = new CountDownLatch(1);
            CountDownLatch releaseHolder = new CountDownLatch(1);
            Thread holder = new Thread(() -> sharedLocks.withLock(player, () -> {
                holderEntered.countDown();
                try {
                    releaseHolder.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            holder.setDaemon(true);
            holder.start();
            assertTrue(holderEntered.await(5, TimeUnit.SECONDS), "外部ロック保持スレッドが開始しなかった");

            Thread worker = new Thread(() -> reconciler.recalculatePlayer(player));
            worker.setDaemon(true);
            worker.start();

            // ロックが本当に効いていれば、外部保持者が解放するまで worker は絶対に完了しない。
            worker.join(300);
            assertTrue(worker.isAlive(),
                    "外部が同じプレイヤーのロックを保持している間、recalculatePlayer は"
                            + "ブロックされていなければならない(ロックを取らずに絶対値で"
                            + "上書きしていた旧実装への回帰)");

            releaseHolder.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive(), "ロック解放後は recalculatePlayer が完了しなければならない");
        }
    }

    @Test
    @DisplayName("別プレイヤーのロックは互いに独立する(粒度がプレイヤー単位であることの確認)")
    void differentPlayersDoNotContendOnTheSharedLock() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            UUID heldPlayer = UUID.randomUUID();
            UUID otherPlayer = UUID.randomUUID();
            PlayerLockRegistry sharedLocks = new PlayerLockRegistry();
            ProgressionCurveReconciler reconciler =
                    new ProgressionCurveReconciler(repository, catalog, sharedLocks, () -> 1);

            CountDownLatch holderEntered = new CountDownLatch(1);
            CountDownLatch releaseHolder = new CountDownLatch(1);
            Thread holder = new Thread(() -> sharedLocks.withLock(heldPlayer, () -> {
                holderEntered.countDown();
                try {
                    releaseHolder.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            holder.setDaemon(true);
            holder.start();
            assertTrue(holderEntered.await(5, TimeUnit.SECONDS), "外部ロック保持スレッドが開始しなかった");

            Thread worker = new Thread(() -> reconciler.recalculatePlayer(otherPlayer));
            worker.setDaemon(true);
            worker.start();
            worker.join(2000);
            assertFalse(worker.isAlive(),
                    "別プレイヤーのロック保持は、対象外プレイヤーの recalculatePlayer を"
                            + "止めてはいけない(全体を1本のロックで直列化していない)");

            releaseHolder.countDown();
            holder.join(2000);
        }
    }
}
