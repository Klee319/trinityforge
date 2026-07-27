package com.trinityforge.progression;

import com.trinityforge.progression.NativeProgressionAdminService.EditMode;
import com.trinityforge.progression.NativeProgressionAdminService.EditStatus;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the point-ledger lost-update race from the audit: {@link NativeProgressionAdminService}
 * ("admin edit") and {@link NativeProgressionService} ("grantExp", as driven by the async
 * dispatcher's per-second drain) must serialize on the same per-player lock when constructed
 * with a shared {@link PlayerLockRegistry}, otherwise an in-flight admin edit's stale
 * read-modify-write can silently roll back a concurrent EXP grant (or vice versa).
 */
class NativeProgressionSharedLockTest {

    @Test
    void adminEditAndGrantExpSerializeOnSharedRegistry() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        UUID player = UUID.randomUUID();
        CountDownLatch enteredAdminWrite = new CountDownLatch(1);
        CountDownLatch releaseAdminWrite = new CountDownLatch(1);

        try (SqliteProgressionRepository sqlite =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            sqlite.saveSkillProgress(player, SkillId.MINING,
                    new SkillProgress(5, 0.0, 0.0, 0, 100));
            sqlite.savePointBalance(player, 4L, 1L);

            ProgressionRepository blockingOnAdminWrite = new DelegatingRepository(sqlite) {
                @Override
                public void saveAdminProgressionEdit(
                        UUID playerId, String skillId, SkillProgress skillProgress,
                        SkillProgress powerProgress, long availablePoints, long spentPoints,
                        String prestigePerkPrefix, int prestigeCount,
                        Collection<String> stripPerkIds) {
                    enteredAdminWrite.countDown();
                    awaitQuietly(releaseAdminWrite);
                    super.saveAdminProgressionEdit(playerId, skillId, skillProgress, powerProgress,
                            availablePoints, spentPoints, prestigePerkPrefix, prestigeCount,
                            stripPerkIds);
                }
            };

            PlayerLockRegistry sharedLocks = new PlayerLockRegistry();
            NativeProgressionAdminService adminService = new NativeProgressionAdminService(
                    blockingOnAdminWrite, catalog, List::of, sharedLocks);
            NativeProgressionService progression = new NativeProgressionService(
                    blockingOnAdminWrite, catalog, id -> 0.0, sharedLocks);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch grantCompleted = new CountDownLatch(1);
                pool.submit(() -> {
                    adminService.edit(player, SkillId.MINING, EditMode.SET, 6, null);
                });
                assertTrue(enteredAdminWrite.await(5, TimeUnit.SECONDS),
                        "admin edit must reach its write while holding the shared lock");

                pool.submit(() -> {
                    progression.grantExp(player, SkillId.MINING, 1.0);
                    grantCompleted.countDown();
                });

                // The shared lock must block grantExp until the admin edit releases it — a
                // regression here (e.g. reverting to per-service unshared locks) would let this
                // grant race the admin edit's stale read-modify-write of the point ledger.
                assertFalse(grantCompleted.await(300, TimeUnit.MILLISECONDS),
                        "grantExp must not proceed while the admin edit holds the shared lock");

                releaseAdminWrite.countDown();
                assertTrue(grantCompleted.await(5, TimeUnit.SECONDS),
                        "grantExp must proceed once the admin edit releases the shared lock");
            } finally {
                pool.shutdownNow();
            }

            PlayerProgression finalState = sqlite.load(player).orElseThrow();
            assertTrue(finalState.skills().get(SkillId.MINING).level() >= 6,
                    "admin edit's write must not be lost");
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Forwards every {@link ProgressionRepository} method to a delegate; subclasses hook in. */
    private static class DelegatingRepository implements ProgressionRepository {
        private final ProgressionRepository delegate;

        DelegatingRepository(ProgressionRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public LoadResult<PlayerProgression> load(UUID playerId) {
            return delegate.load(playerId);
        }

        @Override
        public void saveSkillProgress(UUID playerId, String skillId, SkillProgress progress) {
            delegate.saveSkillProgress(playerId, skillId, progress);
        }

        @Override
        public void savePointBalance(UUID playerId, long availablePoints, long spentPoints) {
            delegate.savePointBalance(playerId, availablePoints, spentPoints);
        }

        @Override
        public void saveProgressionTransition(
                UUID playerId, String skillId, SkillProgress skillProgress,
                SkillProgress powerProgress, long availablePoints, long spentPoints) {
            delegate.saveProgressionTransition(
                    playerId, skillId, skillProgress, powerProgress, availablePoints, spentPoints);
        }

        @Override
        public void saveAdminProgressionEdit(
                UUID playerId, String skillId, SkillProgress skillProgress,
                SkillProgress powerProgress, long availablePoints, long spentPoints,
                String prestigePerkPrefix, int prestigeCount, Collection<String> stripPerkIds) {
            delegate.saveAdminProgressionEdit(
                    playerId, skillId, skillProgress, powerProgress, availablePoints, spentPoints,
                    prestigePerkPrefix, prestigeCount, stripPerkIds);
        }

        @Override
        public boolean unlockPerk(UUID playerId, String perkId, long pointCost) {
            return delegate.unlockPerk(playerId, perkId, pointCost);
        }

        @Override
        public boolean prestige(UUID playerId, String skillId, String ordinaryPerkPrefix,
                                String prestigePerkId, SkillProgress resetProgress, long refundPoints) {
            return delegate.prestige(playerId, skillId, ordinaryPerkPrefix, prestigePerkId,
                    resetProgress, refundPoints);
        }

        @Override
        public LoadResult<Set<String>> loadPerkIds(UUID playerId) {
            return delegate.loadPerkIds(playerId);
        }

        @Override
        public LoadResult<Map<String, Long>> loadPerkCosts(UUID playerId) {
            return delegate.loadPerkCosts(playerId);
        }

        @Override
        public Collection<UUID> listPlayerIds() {
            return delegate.listPlayerIds();
        }

        @Override
        public void resetPlayer(UUID playerId) {
            delegate.resetPlayer(playerId);
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
