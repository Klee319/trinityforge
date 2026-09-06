package com.trinityforge.progression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.DoubleAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W-314 タスク1の回帰テスト。{@link NativeExperienceDispatcher#drain} は旧実装だと
 * {@code AtomicReference<Map>#getAndSet} で {@code pending} の参照を丸ごと差し替えていた。
 * {@link NativeExperienceDispatcher#grant} が「現在の参照を読む→そこへ書く」の2段だったため、
 * この2段の間に {@code drain} が参照を差し替えると、grant の書き込み先は
 * もう {@code pending} から辿れない旧マップになり、そのEXPは例外もログも無いまま消えていた。
 *
 * <p>修正後(単一マップ + {@code remove(key, value)} によるキー単位のCAS回収)では、
 * 回収の最中に割り込んだ grant は必ず「値の不一致」として検知され、削除されずに
 * {@code pending} へ残る(＝次回の drain へ確実に繰り越される)。1件目のテストは
 * {@link NativeExperienceDispatcher#collectionRaceHookForTests} でこのレース窓を
 * 実スレッドの競争に頼らず決定的に再現し、合計が保存されることを検証する。
 */
class NativeExperienceDispatcherLostUpdateTest {

    private static NativeProgressionService.GrantResult grantResultOf(String skillId) {
        return new NativeProgressionService.GrantResult(skillId, null, null, 0, 0);
    }

    @Test
    @DisplayName("drainの収集ウィンドウ中に割り込んだgrantは消えず、次回drainで合計が保存される")
    void grantDuringCollectionWindowIsNotLostButDeferred() {
        NativeProgressionService progression = mock(NativeProgressionService.class);
        UUID playerId = UUID.randomUUID();
        DoubleAdder totalGranted = new DoubleAdder();
        when(progression.grantExp(any(), any(), anyDouble())).thenAnswer(invocation -> {
            double amount = invocation.getArgument(2);
            totalGranted.add(amount);
            return grantResultOf(invocation.getArgument(1));
        });

        NativeExperienceDispatcher dispatcher = new NativeExperienceDispatcher(progression);
        dispatcher.grant(playerId, "DIGGING", 10.0);
        // スナップショット取得後・remove試行前に割り込む(=「回収中に飛んできたgrant」を決定的に再現)。
        dispatcher.collectionRaceHookForTests = () -> dispatcher.grant(playerId, "DIGGING", 5.0);

        dispatcher.drain();
        // 割り込んだ5.0を含む15.0はこの回では回収できていないはず(remove(key,10.0)が値不一致で失敗する)。
        verify(progression, never()).grantExp(playerId, "DIGGING", 15.0);

        // フックを外して次回drain(=1秒後の通常サイクルを模す)を実行すると、合算済みの15.0が丸ごと収集される。
        dispatcher.collectionRaceHookForTests = () -> { };
        dispatcher.drain();

        assertEquals(15.0, totalGranted.sum(), 1e-9,
                "回収中に割り込んだ分もあわせて、合計は1円も失われてはいけない");
    }

    @Test
    @DisplayName("多数スレッドからのgrantとdrainの並走でも合計は必ず保存される")
    void concurrentGrantsAndDrainsPreserveTotal() throws Exception {
        NativeProgressionService progression = mock(NativeProgressionService.class);
        DoubleAdder totalGranted = new DoubleAdder();
        when(progression.grantExp(any(), any(), anyDouble())).thenAnswer(invocation -> {
            double amount = invocation.getArgument(2);
            totalGranted.add(amount);
            return grantResultOf(invocation.getArgument(1));
        });

        NativeExperienceDispatcher dispatcher = new NativeExperienceDispatcher(progression);
        UUID playerId = UUID.randomUUID();
        String skillId = "MINING";

        int grantThreads = 6;
        int grantsPerThread = 2000;
        double amountPerGrant = 1.0;
        double expectedTotal = grantThreads * grantsPerThread * amountPerGrant;

        ExecutorService pool = Executors.newFixedThreadPool(grantThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch grantersDone = new CountDownLatch(grantThreads);
        java.util.concurrent.atomic.AtomicBoolean keepDraining = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            for (int t = 0; t < grantThreads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < grantsPerThread; i++) {
                            dispatcher.grant(playerId, skillId, amountPerGrant);
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        grantersDone.countDown();
                    }
                });
            }
            // 収集と付与を意図的に高頻度で並走させる別スレッド。
            pool.submit(() -> {
                try {
                    start.await();
                    while (keepDraining.get()) {
                        dispatcher.drain();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });

            start.countDown();
            assertTrue(grantersDone.await(30, TimeUnit.SECONDS), "grant側スレッドが時間内に終わらなかった");
            keepDraining.set(false);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
        // 最終ドレインで、まだpendingに残っている分(最後のdrainループとの兼ね合い)を確実に回収する。
        dispatcher.drain();

        assertEquals(expectedTotal, totalGranted.sum(), 1e-6,
                "並走するgrantとdrainのもとでも、合計EXPは1件も失われてはいけない");
    }
}
