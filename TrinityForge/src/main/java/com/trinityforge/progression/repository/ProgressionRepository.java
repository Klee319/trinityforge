package com.trinityforge.progression.repository;

import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillProgress;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence port for player progression state: skill levels/EXP, point balances, and
 * perk unlock state. Implementations are expected to be thread-compatible for independent
 * players (different UUIDs) but may serialize access within a single player's record.
 *
 * <p>All mutation methods are <em>synchronous and durable</em>: a successful return means the
 * change has been committed to persistent storage. {@link #flush()} ensures any pending I/O
 * completes before returning. {@link #close()} flushes and releases resources; once closed,
 * behaviour of other methods is undefined.
 *
 * <p>Perk ownership is not embedded in {@link PlayerProgression} — use {@link #loadPerkIds}
 * separately to keep reads lightweight.
 */
public interface ProgressionRepository extends AutoCloseable {

    /**
     * Loads the full progression snapshot for the player, including all saved skill states
     * and point balances. Returns {@link LoadResult#missing()} if the player has no persisted
     * data; {@link LoadResult#failed(Throwable)} on storage errors.
     */
    LoadResult<PlayerProgression> load(UUID playerId);

    /**
     * Persists the current state of one skill for the player.
     * Uses insert-or-update semantics (upsert); never REPLACE.
     */
    void saveSkillProgress(UUID playerId, String skillId, SkillProgress progress);

    /**
     * Persists the player's point balances (available and spent).
     * Uses insert-or-update semantics (upsert); never REPLACE.
     */
    void savePointBalance(UUID playerId, long availablePoints, long spentPoints);

    /** Atomically saves a skill transition, optional POWER transition, and point balance. */
    void saveProgressionTransition(UUID playerId, String skillId, SkillProgress skillProgress,
                                   SkillProgress powerProgress, long availablePoints, long spentPoints);

    /**
     * Atomically saves an administrator-authored level transition and, when
     * {@code prestigePerkPrefix} is non-null, reconciles the permanent prestige perks to exactly
     * tiers {@code 1..prestigeCount}. {@code stripPerkIds} are ordinary node perks removed because
     * the new skill level no longer meets their requirements.
     */
    void saveAdminProgressionEdit(UUID playerId, String skillId, SkillProgress skillProgress,
                                  SkillProgress powerProgress, long availablePoints, long spentPoints,
                                  String prestigePerkPrefix, int prestigeCount,
                                  Collection<String> stripPerkIds);

    /**
     * Atomically:
     * <ol>
     *   <li>Checks that the player has at least {@code pointCost} available points.</li>
     *   <li>Checks that {@code perkId} is not already unlocked for this player.</li>
     *   <li>Deducts {@code pointCost} from available, adds it to spent.</li>
     *   <li>Records {@code perkId} as unlocked.</li>
     * </ol>
     * Rolls back all changes if any check fails.
     *
     * @return {@code true} if the unlock succeeded; {@code false} if the player has
     *         insufficient points, no balance row at all, or the perk is already unlocked
     */
    boolean unlockPerk(UUID playerId, String perkId, long pointCost);

    /**
     * Atomically resets one skill, refunds its ordinary node costs, removes those node perks, and
     * records the permanent prestige perk.
     */
    boolean prestige(UUID playerId, String skillId, String ordinaryPerkPrefix,
                     String prestigePerkId, SkillProgress resetProgress, long refundPoints);

    /**
     * Atomically performs prestige while retaining selected ordinary perks with their existing
     * persisted metadata, including {@code purchase_cost}. Implementations that do not support
     * retention fail closed instead of committing a partial prestige.
     */
    default boolean prestige(UUID playerId, String skillId, String ordinaryPerkPrefix,
                             String prestigePerkId, SkillProgress resetProgress, long refundPoints,
                             Set<String> retainedOrdinaryPerkIds) {
        if (retainedOrdinaryPerkIds == null || retainedOrdinaryPerkIds.isEmpty()) {
            return prestige(playerId, skillId, ordinaryPerkPrefix,
                    prestigePerkId, resetProgress, refundPoints);
        }
        return false;
    }

    /**
     * Returns the effective set of unlocked perk IDs for the player.
     * On success with no rows, returns {@link LoadResult#found(Set)} with an empty set.
     */
    LoadResult<Set<String>> loadPerkIds(UUID playerId);

    /**
     * Returns stored purchase costs for unlocked perks ({@code perkId → pointCost}).
     * On success with no rows, returns {@link LoadResult#found(Map)} with an empty map.
     */
    LoadResult<Map<String, Long>> loadPerkCosts(UUID playerId);

    /** Returns every player UUID that has at least one persisted progression row. */
    java.util.Collection<UUID> listPlayerIds();

    /** Atomically removes all native progression rows for one player. */
    void resetPlayer(UUID playerId);

    /** Ensures all in-memory state is written to durable storage before returning. */
    void flush();

    /**
     * Flushes and releases all resources.
     * Implementations must not throw checked exceptions from this method.
     */
    @Override
    void close();
}
