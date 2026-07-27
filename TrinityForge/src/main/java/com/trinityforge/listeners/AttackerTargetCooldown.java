package com.trinityforge.listeners;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared in-memory per-(attackerId, targetId) cooldown gate, used to close two related exploit classes:
 * unbounded weapon-skill EXP farming against a target that never dies ({@link CombatListener}), and
 * semi-AFK armor-EXP farming from repeated near-zero hits by the same attacker ({@link
 * NativeSkillExperienceListener}). Bukkit-free (no {@code Player}/{@code Entity} dependency; the caller
 * passes {@link UUID}s), so it is directly unit-testable.
 *
 * <p>Combat events are always handled on the Bukkit main thread (synchronous), so no external
 * synchronization is required by callers; {@link ConcurrentHashMap} is used defensively only, not for
 * cross-thread correctness. {@code now} is an explicit parameter (not read internally) so tests can drive
 * the clock deterministically.
 */
final class AttackerTargetCooldown {

    private static final int DEFAULT_SWEEP_INTERVAL = 200;

    private final Map<String, Long> expiryByPairKey = new ConcurrentHashMap<>();
    private final int sweepInterval;
    private int accessCount = 0;

    AttackerTargetCooldown() {
        this(DEFAULT_SWEEP_INTERVAL);
    }

    /** @param sweepInterval accesses between opportunistic expired-entry sweeps (tests use a small value). */
    AttackerTargetCooldown(int sweepInterval) {
        this.sweepInterval = Math.max(1, sweepInterval);
    }

    /**
     * True when {@code (attacker, target)} is still within its cooldown as of {@code nowMillis}; otherwise
     * records a fresh cooldown expiry ({@code nowMillis + cooldownSeconds * 1000}) and returns false. A
     * non-positive {@code cooldownSeconds} always returns false (cooldown disabled) without recording
     * anything. Every call opportunistically sweeps expired entries once every {@link #sweepInterval}
     * accesses so a long play session never accumulates unbounded stale pairs.
     */
    boolean isOnCooldownAndRefresh(UUID attacker, UUID target, double cooldownSeconds, long nowMillis) {
        if (cooldownSeconds <= 0.0) {
            return false;
        }
        String key = attacker + ":" + target;
        Long expiry = expiryByPairKey.get(key);
        boolean onCooldown = expiry != null && expiry > nowMillis;
        if (!onCooldown) {
            expiryByPairKey.put(key, nowMillis + (long) (cooldownSeconds * 1000.0));
        }
        if (++accessCount >= sweepInterval) {
            accessCount = 0;
            expiryByPairKey.entrySet().removeIf(e -> e.getValue() <= nowMillis);
        }
        return onCooldown;
    }

    /** Test/diagnostic hook: number of tracked (attacker, target) pairs currently held in memory. */
    int trackedPairCount() {
        return expiryByPairKey.size();
    }
}
