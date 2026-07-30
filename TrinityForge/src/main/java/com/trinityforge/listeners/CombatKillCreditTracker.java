package com.trinityforge.listeners;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Main-thread damage-contribution ledger for kill-based HEAVY_WEAPONS/LIGHT_WEAPONS EXP.
 *
 * <p>Damage is accumulated independently for each attacker and weapon skill. Recording clamps each hit
 * to the victim health that remained before the hit, so overkill cannot increase EXP. Consumption turns
 * accumulated damage into max-health shares and proportionally normalizes healed combat whose recorded
 * damage exceeds max health, ensuring that all shares for one death sum to at most {@code 1.0}.
 */
final class CombatKillCreditTracker {

    private static final int MAX_TRACKED_VICTIMS = 4_096;
    static final long CREDIT_TTL_MILLIS = 5 * 60 * 1_000L;
    private static final int SWEEP_INTERVAL = 128;

    record Credit(UUID attackerId, String skill, double share) {
    }

    private record Attribution(UUID attackerId, String skill) {
    }

    private static final class VictimLedger {
        private final Map<Attribution, Double> damageByAttribution = new LinkedHashMap<>();
        private long recordedAtMillis;

        private VictimLedger(long recordedAtMillis) {
            this.recordedAtMillis = recordedAtMillis;
        }
    }

    private final Map<UUID, VictimLedger> credits =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, VictimLedger> eldest) {
                    return size() > MAX_TRACKED_VICTIMS;
                }
            };
    private int recordsSinceSweep;

    void record(UUID victimId, UUID attackerId, String skill,
                double damage, double victimHealthBeforeHit) {
        record(victimId, attackerId, skill, damage, victimHealthBeforeHit,
                System.currentTimeMillis());
    }

    void record(UUID victimId, UUID attackerId, String skill,
                double damage, double victimHealthBeforeHit, long nowMillis) {
        if (victimId == null || attackerId == null || skill == null || skill.isBlank()
                || !Double.isFinite(damage) || damage <= 0.0
                || !Double.isFinite(victimHealthBeforeHit) || victimHealthBeforeHit <= 0.0) {
            return;
        }
        double actualDamage = Math.min(damage, victimHealthBeforeHit);
        VictimLedger ledger = credits.computeIfAbsent(victimId, ignored -> new VictimLedger(nowMillis));
        ledger.recordedAtMillis = nowMillis;
        Attribution attribution = new Attribution(attackerId, skill);
        ledger.damageByAttribution.merge(attribution, actualDamage,
                CombatKillCreditTracker::saturatedAdd);
        if (++recordsSinceSweep >= SWEEP_INTERVAL) {
            recordsSinceSweep = 0;
            credits.values().removeIf(candidate -> expired(candidate, nowMillis));
        }
    }

    void clear(UUID victimId) {
        if (victimId != null) credits.remove(victimId);
    }

    List<Credit> consume(UUID victimId, double maxHealth) {
        return consume(victimId, maxHealth, System.currentTimeMillis());
    }

    List<Credit> consume(UUID victimId, double maxHealth, long nowMillis) {
        if (victimId == null) return List.of();
        VictimLedger ledger = credits.remove(victimId);
        if (ledger == null || expired(ledger, nowMillis)
                || !Double.isFinite(maxHealth) || maxHealth <= 0.0) {
            return List.of();
        }
        double largestDamage = ledger.damageByAttribution.values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);
        if (!Double.isFinite(largestDamage) || largestDamage <= 0.0) {
            return List.of();
        }
        double scaledTotalDamage = ledger.damageByAttribution.values().stream()
                .mapToDouble(damage -> damage / largestDamage)
                .sum();
        double scaledShareDenominator = Math.max(maxHealth / largestDamage, scaledTotalDamage);
        List<Credit> result = new ArrayList<>(ledger.damageByAttribution.size());
        ledger.damageByAttribution.forEach((attribution, damage) ->
                result.add(new Credit(attribution.attackerId(), attribution.skill(),
                        (damage / largestDamage) / scaledShareDenominator)));
        return List.copyOf(result);
    }

    void forgetAttacker(UUID attackerId) {
        if (attackerId == null) return;
        credits.values().forEach(ledger ->
                ledger.damageByAttribution.keySet().removeIf(
                        attribution -> attackerId.equals(attribution.attackerId())));
        credits.values().removeIf(ledger -> ledger.damageByAttribution.isEmpty());
    }

    int trackedCount() {
        return credits.size();
    }

    private static boolean expired(VictimLedger ledger, long nowMillis) {
        return nowMillis - ledger.recordedAtMillis > CREDIT_TTL_MILLIS;
    }

    private static double saturatedAdd(double first, double second) {
        double sum = first + second;
        return Double.isFinite(sum) ? sum : Double.MAX_VALUE;
    }
}
