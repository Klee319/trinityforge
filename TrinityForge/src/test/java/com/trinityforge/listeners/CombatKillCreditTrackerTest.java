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

    /**
     * N5(2026-07-31): ARCHERY も台帳経由(討伐時ベース)になったので、弓と近接を混ぜて削った場合の
     * 按分を固定する。同一攻撃者でもスキルが違えば別エントリになり、share の総和は 1.0 を超えない
     * (=「キル1回分」を2スキルで分け合う。水増しは起きないが単一武器より伸びが遅い、が仕様)。
     *
     * <p>これまで skill 引数に渡していたのは HEAVY/LIGHT だけで、弓を混ぜたときの挙動は未検証だった。
     */
    @Test
    void archeryContributionsShareTheKillWithMeleeWithoutInflatingTheTotal() {
        CombatKillCreditTracker tracker = new CombatKillCreditTracker();
        UUID victim = UUID.randomUUID();
        UUID archer = UUID.randomUUID();
        UUID mixed = UUID.randomUUID();

        tracker.record(victim, archer, SkillId.ARCHERY, 40.0, 100.0);
        // 同一プレイヤーが弓で削ってから近接で仕留めた場合、ARCHERY と LIGHT_WEAPONS の2エントリになる。
        tracker.record(victim, mixed, SkillId.ARCHERY, 30.0, 60.0);
        tracker.record(victim, mixed, SkillId.LIGHT_WEAPONS, 30.0, 30.0);

        List<CombatKillCreditTracker.Credit> credits = tracker.consume(victim, 100.0);

        assertEquals(3, credits.size());
        assertCredit(credits, archer, SkillId.ARCHERY, 0.40);
        assertCredit(credits, mixed, SkillId.ARCHERY, 0.30);
        assertCredit(credits, mixed, SkillId.LIGHT_WEAPONS, 0.30);
        assertEquals(1.0, credits.stream().mapToDouble(CombatKillCreditTracker.Credit::share).sum(), 1e-9);
    }

    /**
     * マルチショット/貫通で1回の発射が複数命中しても、被弾前HPでクランプされたダメージが足されるだけで
     * 「命中回数ぶん払う」ことにはならない(per-hit 方式ではここが3回払いだった)。
     */
    @Test
    void multipleArrowHitsOnOneVictimAccumulateInsteadOfPayingPerHit() {
        CombatKillCreditTracker tracker = new CombatKillCreditTracker();
        UUID victim = UUID.randomUUID();
        UUID archer = UUID.randomUUID();

        tracker.record(victim, archer, SkillId.ARCHERY, 10.0, 20.0);
        tracker.record(victim, archer, SkillId.ARCHERY, 10.0, 10.0);
        tracker.record(victim, archer, SkillId.ARCHERY, 10.0, 1.0);

        List<CombatKillCreditTracker.Credit> credits = tracker.consume(victim, 20.0);
        assertEquals(1, credits.size(), "同一(攻撃者,スキル)は1エントリへ集約される");
        assertEquals(1.0, credits.getFirst().share(), 1e-9,
                "被弾前HPでクランプされるので、過剰ダメージでも share は 1.0 を超えない");
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
