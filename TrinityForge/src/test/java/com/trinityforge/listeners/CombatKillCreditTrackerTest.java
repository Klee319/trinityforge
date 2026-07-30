package com.trinityforge.listeners;

import com.trinityforge.progression.core.SkillId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatKillCreditTrackerTest {

    @Test
    void accumulatesDamageByAttackerAndWeaponSkillAndConsumesEveryContributionOnce() {
        CombatKillCreditTracker tracker = new CombatKillCreditTracker();
        UUID victim = UUID.randomUUID();
        UUID firstAttacker = UUID.randomUUID();
        UUID secondAttacker = UUID.randomUUID();

        tracker.record(victim, firstAttacker, SkillId.HEAVY_WEAPONS, 10.0, 100.0);
        tracker.record(victim, firstAttacker, SkillId.HEAVY_WEAPONS, 20.0, 90.0);
        tracker.record(victim, firstAttacker, SkillId.LIGHT_WEAPONS, 20.0, 70.0);
        tracker.record(victim, secondAttacker, SkillId.HEAVY_WEAPONS, 50.0, 50.0);

        List<CombatKillCreditTracker.Credit> credits = tracker.consume(victim, 100.0);

        assertEquals(3, credits.size());
        assertCredit(credits, firstAttacker, SkillId.HEAVY_WEAPONS, 0.30);
        assertCredit(credits, firstAttacker, SkillId.LIGHT_WEAPONS, 0.20);
        assertCredit(credits, secondAttacker, SkillId.HEAVY_WEAPONS, 0.50);
        assertTrue(tracker.consume(victim, 100.0).isEmpty(), "a death pays each ledger only once");
    }

    @Test
    void overkillOnlyCountsHealthRemainingBeforeTheHit() {
        CombatKillCreditTracker tracker = new CombatKillCreditTracker();
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();

        tracker.record(victim, attacker, SkillId.HEAVY_WEAPONS, 500.0, 12.0);

        List<CombatKillCreditTracker.Credit> credits = tracker.consume(victim, 100.0);
        assertEquals(1, credits.size());
        assertEquals(0.12, credits.getFirst().share(), 1e-9);
    }

    @Test
    void healingCannotMakeTotalSharesExceedOne() {
        CombatKillCreditTracker tracker = new CombatKillCreditTracker();
        UUID victim = UUID.randomUUID();
        UUID firstAttacker = UUID.randomUUID();
        UUID secondAttacker = UUID.randomUUID();

        // The victim heals between hits, so both hits deal 75 actual damage despite max health 100.
        tracker.record(victim, firstAttacker, SkillId.HEAVY_WEAPONS, 75.0, 100.0);
        tracker.record(victim, secondAttacker, SkillId.LIGHT_WEAPONS, 75.0, 100.0);

        List<CombatKillCreditTracker.Credit> credits = tracker.consume(victim, 100.0);
        assertCredit(credits, firstAttacker, SkillId.HEAVY_WEAPONS, 0.50);
        assertCredit(credits, secondAttacker, SkillId.LIGHT_WEAPONS, 0.50);
        assertEquals(1.0, credits.stream().mapToDouble(CombatKillCreditTracker.Credit::share).sum(), 1e-9);
    }

    @Test
    void invalidContributionsAreIgnoredWithoutErasingPriorWeaponDamage() {
        CombatKillCreditTracker tracker = new CombatKillCreditTracker();
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();

        tracker.record(victim, attacker, SkillId.HEAVY_WEAPONS, 40.0, 100.0);
        tracker.record(victim, attacker, "", 60.0, 60.0);
        tracker.record(victim, attacker, null, 60.0, 60.0);
        tracker.record(victim, attacker, SkillId.LIGHT_WEAPONS, 0.0, 60.0);

        List<CombatKillCreditTracker.Credit> credits = tracker.consume(victim, 100.0);
        assertEquals(1, credits.size());
        assertCredit(credits, attacker, SkillId.HEAVY_WEAPONS, 0.40);
    }

    @Test
    void abandonedTargetsCannotGrowTheLedgerWithoutBound() {
        CombatKillCreditTracker tracker = new CombatKillCreditTracker();
        UUID attacker = UUID.randomUUID();
        for (int i = 0; i < 20_000; i++) {
            tracker.record(UUID.randomUUID(), attacker, SkillId.HEAVY_WEAPONS, 1.0, 20.0);
        }
        assertTrue(tracker.trackedCount() <= 4_096);
    }

    @Test
    void despawnedOrUnloadedVictimCreditExpiresAndCannotPayLater() {
        CombatKillCreditTracker tracker = new CombatKillCreditTracker();
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        long recordedAt = 1_000L;
        tracker.record(victim, attacker, SkillId.LIGHT_WEAPONS, 10.0, 20.0, recordedAt);

        assertTrue(tracker.consume(victim, 20.0,
                recordedAt + CombatKillCreditTracker.CREDIT_TTL_MILLIS + 1).isEmpty());
    }

    @Test
    void forgettingOneAttackerPreservesOtherPlayersContributions() {
        CombatKillCreditTracker tracker = new CombatKillCreditTracker();
        UUID victim = UUID.randomUUID();
        UUID departing = UUID.randomUUID();
        UUID remaining = UUID.randomUUID();
        tracker.record(victim, departing, SkillId.HEAVY_WEAPONS, 50.0, 100.0);
        tracker.record(victim, remaining, SkillId.LIGHT_WEAPONS, 50.0, 50.0);

        tracker.forgetAttacker(departing);

        List<CombatKillCreditTracker.Credit> credits = tracker.consume(victim, 100.0);
        assertEquals(1, credits.size());
        assertCredit(credits, remaining, SkillId.LIGHT_WEAPONS, 0.50);
    }

    @Test
    void clearingInvalidVictimDropsTheWholeLedger() {
        CombatKillCreditTracker tracker = new CombatKillCreditTracker();
        UUID victim = UUID.randomUUID();
        tracker.record(victim, UUID.randomUUID(), SkillId.HEAVY_WEAPONS, 10.0, 20.0);
        tracker.record(victim, UUID.randomUUID(), SkillId.LIGHT_WEAPONS, 10.0, 10.0);

        tracker.clear(victim);

        assertTrue(tracker.consume(victim, 20.0).isEmpty());
        assertEquals(0, tracker.trackedCount());
    }

    private static void assertCredit(List<CombatKillCreditTracker.Credit> credits,
                                     UUID attacker, String skill, double expectedShare) {
        CombatKillCreditTracker.Credit credit = credits.stream()
                .filter(candidate -> attacker.equals(candidate.attackerId()) && skill.equals(candidate.skill()))
                .findFirst()
                .orElseThrow();
        assertEquals(expectedShare, credit.share(), 1e-9);
    }
}
