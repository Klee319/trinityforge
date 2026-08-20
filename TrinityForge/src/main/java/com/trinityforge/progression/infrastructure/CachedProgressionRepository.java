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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Write-through cache over a {@link ProgressionRepository}. Cache updates only after successful
 * mutations; failed loads are never cached as missing/new players. Does not hold a monitor across
 * delegate I/O so the DB executor can run without stacking locks on the calling thread.
 */
public final class CachedProgressionRepository implements ProgressionRepository {

    private final ProgressionRepository delegate;
    private final LongSupplier ttlMillis;
    private final LongSupplier clockMillis;
    private final ConcurrentHashMap<UUID, CacheEntry<PlayerProgression>> progressionCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CacheEntry<Set<String>>> perkCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CacheEntry<Map<String, Long>>> perkCostCache = new ConcurrentHashMap<>();
    // Per-player generation, bumped on every invalidate BEFORE the cache entry is removed. A load
    // that started before an invalidate captured an older generation and must not resurrect the
    // stale snapshot it read; the guarded put below rejects it. Closes the read-after-write race
    // where an async preload's put lands after a concurrent write invalidated the cache.
    private final ConcurrentHashMap<UUID, Long> cacheGen = new ConcurrentHashMap<>();

    public CachedProgressionRepository(ProgressionRepository delegate) {
        this(delegate, () -> Long.MAX_VALUE, System::currentTimeMillis);
    }

    public CachedProgressionRepository(ProgressionRepository delegate, LongSupplier ttlMillis) {
        this(delegate, ttlMillis, System::currentTimeMillis);
    }

    CachedProgressionRepository(ProgressionRepository delegate, LongSupplier ttlMillis,
                                LongSupplier clockMillis) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ttlMillis = Objects.requireNonNull(ttlMillis, "ttlMillis");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    @Override
    public LoadResult<PlayerProgression> load(UUID playerId) {
        PlayerProgression cached = freshValue(progressionCache, playerId);
        if (cached != null) {
            return LoadResult.found(cached);
        }
        long gen = cacheGen.getOrDefault(playerId, 0L);
        LoadResult<PlayerProgression> result = delegate.load(playerId);
        if (result.isFound()) {
            cacheIfCurrent(progressionCache, playerId, result.orElseThrow(), gen);
        }
        return result;
    }

    @Override
    public LoadResult<Set<String>> loadPerkIds(UUID playerId) {
        Set<String> cached = freshValue(perkCache, playerId);
        if (cached != null) {
            return LoadResult.found(cached);
        }
        long gen = cacheGen.getOrDefault(playerId, 0L);
        LoadResult<Set<String>> result = delegate.loadPerkIds(playerId);
        if (result.isFound()) {
            cacheIfCurrent(perkCache, playerId, result.orElseThrow(), gen);
        } else if (result.isMissing()) {
            cacheIfCurrent(perkCache, playerId, Set.of(), gen);
            return LoadResult.found(Set.of());
        }
        return result;
    }

    @Override
    public LoadResult<Map<String, Long>> loadPerkCosts(UUID playerId) {
        Map<String, Long> cached = freshValue(perkCostCache, playerId);
        if (cached != null) {
            return LoadResult.found(cached);
        }
        long gen = cacheGen.getOrDefault(playerId, 0L);
        LoadResult<Map<String, Long>> result = delegate.loadPerkCosts(playerId);
        if (result.isFound()) {
            cacheIfCurrent(perkCostCache, playerId, result.orElseThrow(), gen);
        } else if (result.isMissing()) {
            Map<String, Long> empty = Map.of();
            cacheIfCurrent(perkCostCache, playerId, empty, gen);
            return LoadResult.found(empty);
        }
        return result;
    }

    /**
     * Installs {@code value} only if no other thread already cached one and no invalidate has bumped
     * the generation since {@code gen} was captured (i.e. the value we loaded is not already stale).
     */
    private <V> void cacheIfCurrent(ConcurrentHashMap<UUID, CacheEntry<V>> cache,
                                    UUID playerId, V value, long gen) {
        long now = clockMillis.getAsLong();
        cache.compute(playerId, (id, existing) -> {
            if (existing != null && isFresh(existing, now)) {
                return existing;
            }
            if (cacheGen.getOrDefault(id, 0L) != gen) {
                return null; // invalidated during the load — do not resurrect a stale snapshot
            }
            return new CacheEntry<>(value, now);
        });
    }

    private <V> V freshValue(ConcurrentHashMap<UUID, CacheEntry<V>> cache, UUID playerId) {
        CacheEntry<V> entry = cache.get(playerId);
        if (entry == null) return null;
        if (isFresh(entry, clockMillis.getAsLong())) return entry.value();
        cache.remove(playerId, entry);
        return null;
    }

    private boolean isFresh(CacheEntry<?> entry, long now) {
        long ttl = Math.max(0L, ttlMillis.getAsLong());
        return now - entry.loadedAtMillis() < ttl;
    }

    @Override
    public void saveSkillProgress(UUID playerId, String skillId, SkillProgress progress) {
        delegate.saveSkillProgress(playerId, skillId, progress);
        invalidate(playerId);
    }

    @Override
    public void savePointBalance(UUID playerId, long availablePoints, long spentPoints) {
        delegate.savePointBalance(playerId, availablePoints, spentPoints);
        invalidate(playerId);
    }

    @Override
    public void saveProgressionTransition(
            UUID playerId, String skillId, SkillProgress skillProgress,
            SkillProgress powerProgress, long availablePoints, long spentPoints) {
        delegate.saveProgressionTransition(
                playerId, skillId, skillProgress, powerProgress, availablePoints, spentPoints);
        invalidate(playerId);
    }

    @Override
    public void saveAdminProgressionEdit(
            UUID playerId, String skillId, SkillProgress skillProgress,
            SkillProgress powerProgress, long availablePoints, long spentPoints,
            String prestigePerkPrefix, int prestigeCount, Collection<String> stripPerkIds) {
        delegate.saveAdminProgressionEdit(
                playerId, skillId, skillProgress, powerProgress, availablePoints, spentPoints,
                prestigePerkPrefix, prestigeCount, stripPerkIds);
        invalidate(playerId);
    }

    @Override
    public boolean unlockPerk(UUID playerId, String perkId, long pointCost) {
        boolean ok = delegate.unlockPerk(playerId, perkId, pointCost);
        if (ok) invalidate(playerId);
        return ok;
    }

    @Override
    public boolean prestige(
            UUID playerId, String skillId, String ordinaryPerkPrefix,
            String prestigePerkId, SkillProgress resetProgress, long refundPoints) {
        boolean ok = delegate.prestige(
                playerId, skillId, ordinaryPerkPrefix, prestigePerkId, resetProgress, refundPoints);
        if (ok) invalidate(playerId);
        return ok;
    }

    @Override
    public boolean prestige(
            UUID playerId, String skillId, String ordinaryPerkPrefix,
            String prestigePerkId, SkillProgress resetProgress, long refundPoints,
            Set<String> retainedOrdinaryPerkIds) {
        boolean ok = delegate.prestige(playerId, skillId, ordinaryPerkPrefix,
                prestigePerkId, resetProgress, refundPoints, retainedOrdinaryPerkIds);
        if (ok) invalidate(playerId);
        return ok;
    }

    @Override
    public Collection<UUID> listPlayerIds() {
        return delegate.listPlayerIds();
    }

    @Override
    public void resetPlayer(UUID playerId) {
        delegate.resetPlayer(playerId);
        invalidate(playerId);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void close() {
        progressionCache.clear();
        perkCache.clear();
        perkCostCache.clear();
        delegate.close();
    }

    /** Warm cache for join / preload paths. */
    public void preload(UUID playerId) {
        load(playerId);
        loadPerkIds(playerId);
        loadPerkCosts(playerId);
    }

    /**
     * Drops cached state for a player (e.g. on quit) to bound memory. Safe: all mutations are
     * durably persisted in the delegate, so a later join re-reads authoritative state.
     */
    public void evict(UUID playerId) {
        invalidate(playerId);
    }

    private void invalidate(UUID playerId) {
        // Bump the generation BEFORE removing so any concurrent in-flight load that captured the
        // old generation is rejected by cacheIfCurrent instead of resurrecting a stale snapshot.
        cacheGen.merge(playerId, 1L, Long::sum);
        progressionCache.remove(playerId);
        perkCache.remove(playerId);
        perkCostCache.remove(playerId);
    }

    private record CacheEntry<V>(V value, long loadedAtMillis) {
    }
}
