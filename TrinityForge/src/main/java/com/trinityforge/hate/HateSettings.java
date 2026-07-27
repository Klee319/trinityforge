package com.trinityforge.hate;

/**
 * Immutable tuning snapshot for the hate/threat subsystem (gap C5). Holds only the
 * leak-control mechanism parameters (caps, decay, TTL) plus the neutral threat/damage
 * coefficient; all values are config-driven ({@code hate/rates.yml}) so balance never
 * requires a code edit.
 *
 * <p>Conservative defaults ({@link #defaults()}) keep this fix balance-neutral: decay is
 * off, caps are generous enough never to bite normal play, and the TTL only reclaims
 * entries that have been untouched far longer than any real fight.
 *
 * @param maxTrackedMobs     hard cap on simultaneously tracked mobs (global LRU eviction)
 * @param maxAttackersPerMob hard cap on attackers per mob (lowest-threat eviction)
 * @param decayEnabled       master switch for time-based threat decay
 * @param decayPerSecond     fraction of threat removed per second when decay is on (0..1)
 * @param entryTtlMillis     drop an attacker entry untouched for this long; 0 disables TTL
 * @param threatPerDamage    threat generated per point of damage (neutral default 1.0)
 */
public record HateSettings(
        int maxTrackedMobs,
        int maxAttackersPerMob,
        boolean decayEnabled,
        double decayPerSecond,
        long entryTtlMillis,
        double threatPerDamage) {

    public HateSettings {
        if (maxTrackedMobs < 1) {
            throw new IllegalArgumentException("maxTrackedMobs must be >= 1, was " + maxTrackedMobs);
        }
        if (maxAttackersPerMob < 1) {
            throw new IllegalArgumentException("maxAttackersPerMob must be >= 1, was " + maxAttackersPerMob);
        }
        if (!(decayPerSecond >= 0.0 && decayPerSecond <= 1.0)) {
            throw new IllegalArgumentException("decayPerSecond must be in [0,1], was " + decayPerSecond);
        }
        if (entryTtlMillis < 0) {
            throw new IllegalArgumentException("entryTtlMillis must be >= 0, was " + entryTtlMillis);
        }
    }

    /** Balance-neutral defaults: decay off, generous caps, 10-minute stale-entry TTL. */
    public static HateSettings defaults() {
        return new HateSettings(5000, 64, false, 0.0, 600_000L, 1.0);
    }
}
