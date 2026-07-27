package com.trinityforge.progression;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Serializes read-modify-write progression mutations for a single player across every service
 * that touches the point ledger (EXP grants, perk unlocks, prestige, admin edits). Distinct
 * players never contend with one another; the same player's mutations always run one at a time
 * so a load-compute-save span from one caller can never be clobbered by another caller's stale
 * absolute overwrite in between.
 *
 * <p>Sharing a single instance across services is what makes the lock effective — each service
 * must be constructed with the same registry instance rather than creating its own.
 */
public final class PlayerLockRegistry {

    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    /** Runs {@code action} while holding the exclusive lock for {@code playerId}, returning its result. */
    public <T> T withLock(UUID playerId, Supplier<T> action) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(action, "action");
        synchronized (lockFor(playerId)) {
            return action.get();
        }
    }

    private Object lockFor(UUID playerId) {
        return locks.computeIfAbsent(playerId, id -> new Object());
    }
}
