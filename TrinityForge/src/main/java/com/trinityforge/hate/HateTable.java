package com.trinityforge.hate;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded, self-evicting aggro/threat store: mob {@literal ->} per-attacker threat (gap C5).
 *
 * <p>The original design gap was a table that grew without bound, never decayed, and never
 * dropped entries on death/removal/unload. This implementation closes all four leak vectors:
 * <ul>
 *   <li><b>Caps</b> – a global cap on tracked mobs (LRU eviction) and a per-mob cap on
 *       attackers (lowest-threat eviction) bound total memory. When the per-mob cap is full, a
 *       brand-new attacker is only admitted if it out-threats the current lowest attacker (see
 *       {@link #addThreat}); otherwise the insertion is silently rejected instead of evicting an
 *       established attacker for a cheap poke.</li>
 *   <li><b>Eviction</b> – {@link #removeMob}, {@link #removeAttacker}, and
 *       {@link #removeMobsInWorld} let the listener drop data on mob death/removal, player
 *       death/quit, and world unload.</li>
 *   <li><b>Decay</b> – {@link #sweep} applies optional config-driven exponential decay.</li>
 *   <li><b>Sweep</b> – {@link #sweep} also reclaims TTL-expired and near-zero entries and any
 *       mob left with no attackers.</li>
 * </ul>
 *
 * <p>Threading: combat events touch this on the server main thread while the periodic sweep
 * runs on an async scheduler thread. Every public method is {@code synchronized} on the
 * instance, so the two never race. The settings reference is swapped atomically on reload.
 */
public final class HateTable {

    /** Entries at or below this threat are garbage, not balance — dropped on sweep. */
    private static final double MIN_THREAT_FLOOR = 0.01;

    private final Map<UUID, MobThreat> mobs = new HashMap<>();
    private HateSettings settings;

    public HateTable(HateSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Swap the tuning snapshot (called after {@code /trinityforge reload}). */
    public synchronized void applySettings(HateSettings newSettings) {
        this.settings = Objects.requireNonNull(newSettings, "settings");
    }

    public synchronized HateSettings settings() {
        return settings;
    }

    public synchronized int trackedMobCount() {
        return mobs.size();
    }

    public synchronized int attackerCount(UUID mobId) {
        MobThreat mob = mobs.get(mobId);
        return mob == null ? 0 : mob.attackers.size();
    }

    public synchronized double threatOf(UUID mobId, UUID attackerId) {
        MobThreat mob = mobs.get(mobId);
        if (mob == null) {
            return 0.0;
        }
        ThreatEntry entry = mob.attackers.get(attackerId);
        return entry == null ? 0.0 : entry.threat;
    }

    /**
     * Accumulate {@code amount} threat from {@code attackerId} onto {@code mobId}. Caps are
     * enforced before any new key is inserted, so the structure can never exceed its bounds.
     * Non-positive or non-finite amounts are ignored. If {@code attackerId} already has an entry,
     * any decay owed since its last touch is applied to the existing threat before {@code amount}
     * is added (see {@link #applyPendingDecay}), so attacking faster than the sweep cadence cannot
     * bypass decay entirely.
     *
     * <p><b>Provisional per-mob cap rule (task item 3):</b> when the per-mob attacker cap is
     * already full and {@code attackerId} is not yet tracked on this mob, the new attacker is
     * admitted only if {@code amount} exceeds the lowest threat currently held on the mob; a
     * lower-or-equal first hit is dropped rather than evicting an established attacker. This
     * favors the incumbent on ties. It is a provisional fairness rule pending the hate-balance
     * design (R2) - revisit if playtesting shows it starves legitimate late-joining attackers.
     */
    public synchronized void addThreat(UUID mobId, UUID attackerId, UUID worldId,
                                       double amount, long nowMillis) {
        Objects.requireNonNull(mobId, "mobId");
        Objects.requireNonNull(attackerId, "attackerId");
        if (!(amount > 0.0) || !Double.isFinite(amount)) {
            return;
        }

        MobThreat mob = mobs.get(mobId);
        if (mob == null) {
            enforceGlobalCap();
            mob = new MobThreat(worldId);
            mobs.put(mobId, mob);
        }
        mob.worldId = worldId;
        mob.lastTouchedMillis = nowMillis;

        ThreatEntry entry = mob.attackers.get(attackerId);
        if (entry == null) {
            int cap = settings.maxAttackersPerMob();
            if (mob.attackers.size() >= cap) {
                UUID lowestId = lowestThreatAttacker(mob);
                if (lowestId != null && amount <= mob.attackers.get(lowestId).threat) {
                    return; // reject: newcomer does not out-threat the weakest incumbent
                }
            }
            enforcePerMobCap(mob);
            entry = new ThreatEntry(0.0, nowMillis);
            mob.attackers.put(attackerId, entry);
        } else {
            applyPendingDecay(entry, nowMillis);
        }
        entry.threat += amount;
        entry.lastAddMillis = nowMillis;
    }

    /**
     * The current highest-threat attacker for a mob (the aggro target), if any. Tie-breaking is
     * fully deterministic, independent of {@link HashMap} iteration order:
     * <ol>
     *   <li>Higher threat wins.</li>
     *   <li>Threat tie: the attacker with the older (smaller) {@code lastAddMillis} wins - the
     *       one whose current threat total was established first is treated as the incumbent, so
     *       a same-threat newcomer cannot silently steal aggro.</li>
     *   <li>Still tied (identical {@code lastAddMillis}): the smaller {@link UUID} wins, purely
     *       to make the choice reproducible; this ordering carries no game-design meaning.</li>
     * </ol>
     */
    public synchronized Optional<UUID> topAttacker(UUID mobId) {
        MobThreat mob = mobs.get(mobId);
        if (mob == null || mob.attackers.isEmpty()) {
            return Optional.empty();
        }
        UUID best = null;
        ThreatEntry bestEntry = null;
        for (Map.Entry<UUID, ThreatEntry> e : mob.attackers.entrySet()) {
            UUID candidateId = e.getKey();
            ThreatEntry candidate = e.getValue();
            if (best == null || isBetterAttacker(candidateId, candidate, best, bestEntry)) {
                best = candidateId;
                bestEntry = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Deterministic ordering used by {@link #topAttacker}; see that method's javadoc. */
    private static boolean isBetterAttacker(UUID candidateId, ThreatEntry candidate,
                                            UUID currentBestId, ThreatEntry currentBest) {
        if (candidate.threat != currentBest.threat) {
            return candidate.threat > currentBest.threat;
        }
        if (candidate.lastAddMillis != currentBest.lastAddMillis) {
            return candidate.lastAddMillis < currentBest.lastAddMillis;
        }
        return candidateId.compareTo(currentBestId) < 0;
    }

    /** Eviction hook: drop all threat data for a mob (death / removal / despawn). */
    public synchronized boolean removeMob(UUID mobId) {
        return mobs.remove(mobId) != null;
    }

    /**
     * Eviction hook: drop an attacker's contributions across every mob (player death / quit).
     * Mobs left with no attackers are removed too. Returns how many mobs were affected.
     */
    public synchronized int removeAttacker(UUID attackerId) {
        Objects.requireNonNull(attackerId, "attackerId");
        int affected = 0;
        Iterator<MobThreat> it = mobs.values().iterator();
        while (it.hasNext()) {
            MobThreat mob = it.next();
            if (mob.attackers.remove(attackerId) != null) {
                affected++;
                if (mob.attackers.isEmpty()) {
                    it.remove();
                }
            }
        }
        return affected;
    }

    /** Eviction hook: drop every mob belonging to a world (world unload). */
    public synchronized int removeMobsInWorld(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        int removed = 0;
        Iterator<MobThreat> it = mobs.values().iterator();
        while (it.hasNext()) {
            if (worldId.equals(it.next().worldId)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Periodic maintenance: apply optional decay, then reclaim TTL-expired / near-zero entries
     * and any now-empty mob. Returns the number of attacker entries removed.
     */
    public synchronized int sweep(long nowMillis) {
        long ttl = settings.entryTtlMillis();
        int removed = 0;

        Iterator<MobThreat> mobIt = mobs.values().iterator();
        while (mobIt.hasNext()) {
            MobThreat mob = mobIt.next();
            Iterator<ThreatEntry> attIt = mob.attackers.values().iterator();
            while (attIt.hasNext()) {
                ThreatEntry entry = attIt.next();
                applyPendingDecay(entry, nowMillis);
                boolean expired = ttl > 0 && (nowMillis - entry.lastAddMillis) > ttl;
                if (expired || entry.threat < MIN_THREAT_FLOOR) {
                    attIt.remove();
                    removed++;
                }
            }
            if (mob.attackers.isEmpty()) {
                mobIt.remove();
            }
        }
        return removed;
    }

    /**
     * Applies any decay owed for the time elapsed since {@code entry.lastDecayMillis}, then stamps
     * the decay clock to {@code nowMillis}. No-op when decay is disabled or the configured rate is
     * zero, leaving {@code lastDecayMillis} untouched in that case (mirrors the pre-fix sweep gate:
     * if decay is later enabled, the very next call decays across the whole idle period at once,
     * which is the existing, intentional behaviour). Shared by {@link #addThreat} (task item 2 -
     * accumulating threat faster than the sweep cadence must not bypass decay) and {@link #sweep}
     * itself, so there is exactly one decay implementation (single-source spirit of task item 5).
     */
    private void applyPendingDecay(ThreatEntry entry, long nowMillis) {
        if (!settings.decayEnabled() || !(settings.decayPerSecond() > 0.0)) {
            return;
        }
        double elapsedSec = Math.max(0.0, (nowMillis - entry.lastDecayMillis) / 1000.0);
        if (elapsedSec > 0.0) {
            entry.threat *= Math.pow(1.0 - settings.decayPerSecond(), elapsedSec);
            entry.lastDecayMillis = nowMillis;
        }
    }

    /** Drop everything (plugin disable). */
    public synchronized void clear() {
        mobs.clear();
    }

    /** Evict least-recently-touched mobs until inserting one more stays within the cap. */
    private void enforceGlobalCap() {
        int cap = settings.maxTrackedMobs();
        while (mobs.size() >= cap) {
            UUID lru = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<UUID, MobThreat> e : mobs.entrySet()) {
                if (e.getValue().lastTouchedMillis < oldest) {
                    oldest = e.getValue().lastTouchedMillis;
                    lru = e.getKey();
                }
            }
            if (lru == null) {
                return;
            }
            mobs.remove(lru);
        }
    }

    /** Evict lowest-threat attackers until inserting one more stays within the cap. */
    private void enforcePerMobCap(MobThreat mob) {
        int cap = settings.maxAttackersPerMob();
        while (mob.attackers.size() >= cap) {
            UUID lowest = lowestThreatAttacker(mob);
            if (lowest == null) {
                return;
            }
            mob.attackers.remove(lowest);
        }
    }

    /** The attacker with the lowest threat on {@code mob}, or {@code null} if it has none. */
    private static UUID lowestThreatAttacker(MobThreat mob) {
        UUID lowest = null;
        double low = Double.POSITIVE_INFINITY;
        for (Map.Entry<UUID, ThreatEntry> e : mob.attackers.entrySet()) {
            if (e.getValue().threat < low) {
                low = e.getValue().threat;
                lowest = e.getKey();
            }
        }
        return lowest;
    }

    /** Per-mob bucket. Mutable, but only ever touched under the {@link HateTable} monitor. */
    private static final class MobThreat {
        private UUID worldId;
        private long lastTouchedMillis;
        private final Map<UUID, ThreatEntry> attackers = new HashMap<>();

        MobThreat(UUID worldId) {
            this.worldId = worldId;
        }
    }

    /** Per-attacker accumulator. Mutable, guarded by the {@link HateTable} monitor. */
    private static final class ThreatEntry {
        private double threat;
        private long lastAddMillis;
        private long lastDecayMillis;

        ThreatEntry(double threat, long nowMillis) {
            this.threat = threat;
            this.lastAddMillis = nowMillis;
            this.lastDecayMillis = nowMillis;
        }
    }
}
