package com.trinityforge.progression;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the mutual-exclusion guarantee that {@link NativeProgressionAdminService} and
 * {@link NativeProgressionService} rely on to avoid the point-ledger lost-update race
 * (concurrent admin edit + gameplay EXP grant racing on the same player).
 */
class PlayerLockRegistryTest {

    @Test
    void withLock_serializesConcurrentActionsForTheSamePlayer() throws Exception {
        PlayerLockRegistry locks = new PlayerLockRegistry();
        UUID player = UUID.randomUUID();
        AtomicInteger concurrentEntries = new AtomicInteger();
        AtomicInteger maxObservedConcurrency = new AtomicInteger();
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    locks.withLock(player, () -> {
                        int current = concurrentEntries.incrementAndGet();
                        maxObservedConcurrency.updateAndGet(prev -> Math.max(prev, current));
                        // Hold the lock briefly so overlapping entries would be observed if the
                        // lock were not actually exclusive.
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        concurrentEntries.decrementAndGet();
                        return null;
                    });
                    done.countDown();
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "all threads must finish");
            assertEquals(1, maxObservedConcurrency.get(),
                    "same-player mutations must never run concurrently");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void withLock_doesNotSerializeDifferentPlayers() throws Exception {
        PlayerLockRegistry locks = new PlayerLockRegistry();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch bFinished = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> locks.withLock(playerA, () -> {
                aEntered.countDown();
                await(bFinished);
                return null;
            }));
            assertTrue(aEntered.await(5, TimeUnit.SECONDS));
            // Player B must be able to acquire its own lock while A still holds its lock.
            pool.submit(() -> locks.withLock(playerB, () -> {
                bFinished.countDown();
                return null;
            }));
            assertTrue(bFinished.await(5, TimeUnit.SECONDS),
                    "a different player's lock must not be blocked by player A's in-flight lock");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
