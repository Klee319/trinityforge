package com.trinityforge.progression.audit;

import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;
import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.runtime.NativePerkService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 contract freeze: documents current (often unsafe) behavior and stubs desired Phase 1 fixes.
 */
class NativeProgressionStabilizationContractsTest {

    @Test
    void loadAfterClose_returnsEmpty_characterizesFailOpen() throws Exception {
        SqliteProgressionRepository repository =
                new SqliteProgressionRepository("jdbc:sqlite::memory:");
        UUID player = UUID.randomUUID();
        repository.saveSkillProgress(player, SkillId.MINING,
                new SkillProgress(5, 0.0, 100.0, 0, 100));
        repository.close();

        assertTrue(repository.load(player).isFailed(),
                "closed repository load must not look like a missing player");
        assertTrue(repository.loadPerkIds(player).isFailed());
    }

    @Test
    void catalogLoadsSixteenSkillsFromClasspath() {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        assertEquals(16, catalog.size());
        assertTrue(catalog.entries().containsKey(SkillId.POWER));
        assertTrue(catalog.entries().containsKey(SkillId.ARS_MAGIC));
    }

    @Test
    @Disabled("Pre-schema-v2: prestige refunded live YAML cost (see desired_prestigeRefundUsesStoredPurchaseCost)")
    void prestigeRefundUsesLiveYamlCost_characterizesMutableConfigRefund() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        SkillNode node = new SkillNode(
                "A", "A", 10, SkillRole.MAIN, null, null, "STONE", 2, "",
                Map.of(), Map.of(), List.of(), List.of(), List.of());
        Prestige prestige = new Prestige(true, 10, "P", "", Map.of(), Map.of(), 1);
        SkillTree cheap = new SkillTree(
                SkillId.MINING, "Mining", "IRON_PICKAXE", "2,10", prestige, Map.of("A", node));
        SkillNode expensiveNode = new SkillNode(
                "A", "A", 10, SkillRole.MAIN, null, null, "STONE", 5, "",
                Map.of(), Map.of(), List.of(), List.of(), List.of());
        SkillTree expensive = new SkillTree(
                SkillId.MINING, "Mining", "IRON_PICKAXE", "2,10", prestige,
                Map.of("A", expensiveNode));

        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService progression =
                    new NativeProgressionService(repository, catalog);
            var holder = new Object() {
                SkillTree tree = cheap;
            };
            NativePerkService perks = new NativePerkService(progression, () -> List.of(holder.tree));
            UUID player = UUID.randomUUID();
            repository.savePointBalance(player, 10L, 0L);
            assertTrue(repository.unlockPerk(player, "mining_perk_a", 2L));
            repository.saveSkillProgress(player, SkillId.MINING,
                    new SkillProgress(10, 0.0, 1000.0, 0, 100));

            holder.tree = expensive;
            assertEquals(NativePerkService.PrestigeResult.PRESTIGED,
                    perks.prestige(player, SkillId.MINING));
            // Refund used live YAML cost 5, not the original purchase cost 2.
            assertEquals(13L, repository.load(player).orElseThrow().availablePoints());
        }
    }

    @Test
    void dispatcherIsolatesBatchEntriesAndRetriesTransientFailures() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        AtomicInteger saves = new AtomicInteger();
        SqliteProgressionRepository delegate =
                new SqliteProgressionRepository("jdbc:sqlite::memory:");
        ProgressionRepository flaky = new ProgressionRepository() {
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
                if (saves.getAndIncrement() == 0) {
                    throw new IllegalStateException("injected batch failure");
                }
                delegate.saveProgressionTransition(
                        playerId, skillId, skillProgress, powerProgress,
                        availablePoints, spentPoints);
            }

            @Override
            public void saveAdminProgressionEdit(
                    UUID playerId, String skillId, SkillProgress skillProgress,
                    SkillProgress powerProgress, long availablePoints, long spentPoints,
                    String prestigePerkPrefix, int prestigeCount,
                    Collection<String> stripPerkIds) {
                delegate.saveAdminProgressionEdit(
                        playerId, skillId, skillProgress, powerProgress,
                        availablePoints, spentPoints, prestigePerkPrefix, prestigeCount,
                        stripPerkIds);
            }

            @Override
            public boolean unlockPerk(UUID playerId, String perkId, long pointCost) {
                return delegate.unlockPerk(playerId, perkId, pointCost);
            }

            @Override
            public boolean prestige(UUID playerId, String skillId, String ordinaryPerkPrefix,
                                    String prestigePerkId, SkillProgress resetProgress,
                                    long refundPoints) {
                return delegate.prestige(playerId, skillId, ordinaryPerkPrefix,
                        prestigePerkId, resetProgress, refundPoints);
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
        };

        NativeProgressionService progression = new NativeProgressionService(flaky, catalog);
        try (NativeExperienceDispatcher dispatcher = new NativeExperienceDispatcher(progression)) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            dispatcher.grant(first, SkillId.MINING, 1.0);
            dispatcher.grant(second, SkillId.FISHING, 1.0);
            dispatcher.drain();
            assertEquals(3, saves.get(), "first grant retries once after transient failure");
            assertTrue(progression.progress(first, SkillId.MINING).isPresent(),
                    "first entry succeeds after retry");
            assertTrue(progression.progress(second, SkillId.FISHING).isPresent(),
                    "second entry must not be dropped when the first grant fails");
        }
    }

    @Test
    void desired_dispatcherIsolatesBatchOnUnexpectedRuntimeExceptionAndDropsAfterRetryLimit()
            throws Exception {
        // Stands in for a malformed exp_level_curve formula: FormulaParseException is a plain
        // RuntimeException that is neither IllegalStateException nor IllegalArgumentException.
        // Before the fix, drain() only caught those two types, so this would propagate out of
        // drain() entirely and silently drop every other pending grant in the same batch.
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        SqliteProgressionRepository delegate =
                new SqliteProgressionRepository("jdbc:sqlite::memory:");
        UUID broken = UUID.randomUUID();
        ProgressionRepository poisoned = new ProgressionRepository() {
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
                if (playerId.equals(broken)) {
                    // Deterministically "malformed formula"-like: always fails, never a
                    // transient IllegalStateException.
                    throw new ArithmeticException("simulated malformed exp_level_curve formula");
                }
                delegate.saveProgressionTransition(
                        playerId, skillId, skillProgress, powerProgress,
                        availablePoints, spentPoints);
            }

            @Override
            public void saveAdminProgressionEdit(
                    UUID playerId, String skillId, SkillProgress skillProgress,
                    SkillProgress powerProgress, long availablePoints, long spentPoints,
                    String prestigePerkPrefix, int prestigeCount,
                    Collection<String> stripPerkIds) {
                delegate.saveAdminProgressionEdit(
                        playerId, skillId, skillProgress, powerProgress,
                        availablePoints, spentPoints, prestigePerkPrefix, prestigeCount,
                        stripPerkIds);
            }

            @Override
            public boolean unlockPerk(UUID playerId, String perkId, long pointCost) {
                return delegate.unlockPerk(playerId, perkId, pointCost);
            }

            @Override
            public boolean prestige(UUID playerId, String skillId, String ordinaryPerkPrefix,
                                    String prestigePerkId, SkillProgress resetProgress,
                                    long refundPoints) {
                return delegate.prestige(playerId, skillId, ordinaryPerkPrefix,
                        prestigePerkId, resetProgress, refundPoints);
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
        };

        NativeProgressionService progression = new NativeProgressionService(poisoned, catalog);
        try (NativeExperienceDispatcher dispatcher = new NativeExperienceDispatcher(progression)) {
            UUID healthy = UUID.randomUUID();
            dispatcher.grant(broken, SkillId.MINING, 1.0);
            dispatcher.grant(healthy, SkillId.FISHING, 1.0);

            // Must not throw out of drain(): the unexpected RuntimeException must be caught,
            // retried up to the bound, and then dropped in isolation.
            assertDoesNotThrow(dispatcher::drain);

            assertTrue(progression.progress(healthy, SkillId.FISHING).isPresent(),
                    "a batch entry that keeps failing must not drop unrelated entries");
            assertTrue(progression.progress(broken, SkillId.MINING).isEmpty(),
                    "the permanently-failing entry itself must be dropped, not silently applied");
        }
    }

    @Test
    void desired_loadFailureDistinctFromMissingPlayer() throws Exception {
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            assertTrue(repository.load(UUID.randomUUID()).isMissing(),
                    "unknown player must be missing, not failed");
        }

        SqliteProgressionRepository repository =
                new SqliteProgressionRepository("jdbc:sqlite::memory:");
        UUID player = UUID.randomUUID();
        repository.saveSkillProgress(player, SkillId.MINING,
                new SkillProgress(1, 0.0, 10.0, 0, 100));
        repository.close();
        assertTrue(repository.load(player).isFailed());
        assertTrue(repository.loadPerkIds(player).isFailed());
    }

    @Test
    void desired_prestigeRefundUsesStoredPurchaseCost() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        SkillNode node = new SkillNode(
                "A", "A", 10, SkillRole.MAIN, null, null, "STONE", 2, "",
                Map.of(), Map.of(), List.of(), List.of(), List.of());
        Prestige prestige = new Prestige(true, 10, "P", "", Map.of(), Map.of(), 1);
        SkillTree cheap = new SkillTree(
                SkillId.MINING, "Mining", "IRON_PICKAXE", "2,10", prestige, Map.of("A", node));
        SkillNode expensiveNode = new SkillNode(
                "A", "A", 10, SkillRole.MAIN, null, null, "STONE", 5, "",
                Map.of(), Map.of(), List.of(), List.of(), List.of());
        SkillTree expensive = new SkillTree(
                SkillId.MINING, "Mining", "IRON_PICKAXE", "2,10", prestige,
                Map.of("A", expensiveNode));

        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService progression =
                    new NativeProgressionService(repository, catalog);
            var holder = new Object() {
                SkillTree tree = cheap;
            };
            NativePerkService perks = new NativePerkService(progression, () -> List.of(holder.tree));
            UUID player = UUID.randomUUID();
            repository.savePointBalance(player, 10L, 0L);
            assertTrue(repository.unlockPerk(player, "mining_perk_a", 2L));
            repository.saveSkillProgress(player, SkillId.MINING,
                    new SkillProgress(10, 0.0, 1000.0, 0, 100));

            holder.tree = expensive;
            assertEquals(NativePerkService.PrestigeResult.PRESTIGED,
                    perks.prestige(player, SkillId.MINING));
            PlayerProgression after = repository.load(player).orElseThrow();
            assertEquals(10L, after.availablePoints());
            assertEquals(0L, after.spentPoints());
            assertEquals(1, after.skills().get(SkillId.MINING).prestige());
            assertTrue(repository.loadPerkIds(player).orElseThrow().contains("mining_perk_ng1"));
        }
    }
}
