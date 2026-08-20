package com.trinityforge.progression.infrastructure;

import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Serializes all progression repository I/O onto a single daemon thread so async EXP drains and
 * main-thread reads never race the non-thread-safe SQLite JDBC connection.
 */
public final class ExecutorProgressionRepository implements ProgressionRepository {

    private static final long TIMEOUT_SECONDS = 15L;

    private final ProgressionRepository delegate;
    private final ExecutorService executor;
    private final Thread dbThread;

    public ExecutorProgressionRepository(ProgressionRepository delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "trinityforge-progression-db");
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
        try {
            this.dbThread = executor.submit(Thread::currentThread).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            executor.shutdownNow();
            throw new IllegalStateException("Failed to start progression DB executor", e);
        }
    }

    private <T> T call(Callable<T> task) {
        if (Thread.currentThread() == dbThread) {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("progression DB call failed", e);
            }
        }
        try {
            return executor.submit(task).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("progression DB call timed out", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException("progression DB call failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("progression DB call interrupted", e);
        }
    }

    private void run(Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }

    @Override
    public LoadResult<PlayerProgression> load(UUID playerId) {
        return call(() -> delegate.load(playerId));
    }

    @Override
    public void saveSkillProgress(UUID playerId, String skillId, SkillProgress progress) {
        run(() -> delegate.saveSkillProgress(playerId, skillId, progress));
    }

    @Override
    public void savePointBalance(UUID playerId, long availablePoints, long spentPoints) {
        run(() -> delegate.savePointBalance(playerId, availablePoints, spentPoints));
    }

    @Override
    public void saveProgressionTransition(UUID playerId, String skillId, SkillProgress skillProgress,
                                          SkillProgress powerProgress, long availablePoints, long spentPoints) {
        run(() -> delegate.saveProgressionTransition(
                playerId, skillId, skillProgress, powerProgress, availablePoints, spentPoints));
    }

    @Override
    public void saveAdminProgressionEdit(UUID playerId, String skillId, SkillProgress skillProgress,
                                         SkillProgress powerProgress, long availablePoints, long spentPoints,
                                         String prestigePerkPrefix, int prestigeCount,
                                         Collection<String> stripPerkIds) {
        run(() -> delegate.saveAdminProgressionEdit(
                playerId, skillId, skillProgress, powerProgress, availablePoints, spentPoints,
                prestigePerkPrefix, prestigeCount, stripPerkIds));
    }

    @Override
    public boolean unlockPerk(UUID playerId, String perkId, long pointCost) {
        return call(() -> delegate.unlockPerk(playerId, perkId, pointCost));
    }

    @Override
    public boolean prestige(UUID playerId, String skillId, String ordinaryPerkPrefix,
                            String prestigePerkId, SkillProgress resetProgress, long refundPoints) {
        return call(() -> delegate.prestige(
                playerId, skillId, ordinaryPerkPrefix, prestigePerkId, resetProgress, refundPoints));
    }

    @Override
    public boolean prestige(UUID playerId, String skillId, String ordinaryPerkPrefix,
                            String prestigePerkId, SkillProgress resetProgress, long refundPoints,
                            Set<String> retainedOrdinaryPerkIds) {
        return call(() -> delegate.prestige(playerId, skillId, ordinaryPerkPrefix,
                prestigePerkId, resetProgress, refundPoints, retainedOrdinaryPerkIds));
    }

    @Override
    public LoadResult<Set<String>> loadPerkIds(UUID playerId) {
        return call(() -> delegate.loadPerkIds(playerId));
    }

    @Override
    public LoadResult<Map<String, Long>> loadPerkCosts(UUID playerId) {
        return call(() -> delegate.loadPerkCosts(playerId));
    }

    @Override
    public Collection<UUID> listPlayerIds() {
        return call(delegate::listPlayerIds);
    }

    @Override
    public void resetPlayer(UUID playerId) {
        run(() -> delegate.resetPlayer(playerId));
    }

    @Override
    public void flush() {
        run(delegate::flush);
    }

    @Override
    public void close() {
        try {
            call(() -> {
                delegate.close();
                return null;
            });
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
