package com.trinityforge.listeners;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AttackerTargetCooldown}: the shared per-(attacker,target) cooldown gate behind the
 * 武器スキルEXP無限farm fix ({@link CombatListener}) and the semi-AFK 防具EXP farm fix
 * ({@link NativeSkillExperienceListener}).
 */
class AttackerTargetCooldownTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void firstHitOnAFreshPairIsNeverOnCooldown() {
        AttackerTargetCooldown tracker = new AttackerTargetCooldown();
        assertFalse(tracker.isOnCooldownAndRefresh(A, B, 10.0, 0L),
                "a never-seen (attacker,target) pair must not be blocked");
    }

    @Test
    void secondHitWithinTheWindowIsOnCooldown() {
        AttackerTargetCooldown tracker = new AttackerTargetCooldown();
        assertFalse(tracker.isOnCooldownAndRefresh(A, B, 10.0, 0L));
        assertTrue(tracker.isOnCooldownAndRefresh(A, B, 10.0, 5_000L),
                "hitting the same target again 5s into a 10s cooldown must be blocked");
    }

    @Test
    void hitAfterTheWindowElapsedIsNotOnCooldown() {
        AttackerTargetCooldown tracker = new AttackerTargetCooldown();
        assertFalse(tracker.isOnCooldownAndRefresh(A, B, 10.0, 0L));
        assertFalse(tracker.isOnCooldownAndRefresh(A, B, 10.0, 10_001L),
                "a hit after the cooldown has fully elapsed must grant again");
    }

    @Test
    void cooldownIsDisabledWhenSecondsIsNonPositive() {
        AttackerTargetCooldown tracker = new AttackerTargetCooldown();
        assertFalse(tracker.isOnCooldownAndRefresh(A, B, 0.0, 0L));
        assertFalse(tracker.isOnCooldownAndRefresh(A, B, 0.0, 1L),
                "cooldownSeconds<=0 must never block, even on an immediate repeat");
    }

    @Test
    void differentTargetIsNeverBlockedByAnUnrelatedPairsCooldown() {
        AttackerTargetCooldown tracker = new AttackerTargetCooldown();
        assertFalse(tracker.isOnCooldownAndRefresh(A, B, 10.0, 0L));
        assertFalse(tracker.isOnCooldownAndRefresh(A, C, 10.0, 1L),
                "switching to a fresh target must grant EXP normally, matching a never-dying-mob workaround "
                        + "not being farmable by kiting between multiple targets in a single cooldown window");
    }

    @Test
    void differentAttackerOnTheSameTargetIsNotBlocked() {
        AttackerTargetCooldown tracker = new AttackerTargetCooldown();
        assertFalse(tracker.isOnCooldownAndRefresh(A, C, 10.0, 0L));
        assertFalse(tracker.isOnCooldownAndRefresh(B, C, 10.0, 1L),
                "a different attacker hitting the same target must not inherit someone else's cooldown");
    }

    @Test
    void opportunisticSweepRemovesExpiredPairsAndBoundsMemory() {
        // Small sweep interval so the test does not need hundreds of calls.
        AttackerTargetCooldown tracker = new AttackerTargetCooldown(4);
        tracker.isOnCooldownAndRefresh(A, B, 1.0, 0L);      // pair 1, expires at 1000ms
        assertEquals(1, tracker.trackedPairCount());
        tracker.isOnCooldownAndRefresh(A, C, 1.0, 0L);      // pair 2, expires at 1000ms
        tracker.isOnCooldownAndRefresh(B, C, 1.0, 0L);      // pair 3, expires at 1000ms
        // 4th access triggers the sweep; "now" (5000ms) is well past every pair's 1000ms expiry.
        tracker.isOnCooldownAndRefresh(B, A, 1.0, 5_000L);  // pair 4 (fresh), triggers sweep
        assertEquals(1, tracker.trackedPairCount(),
                "the sweep must have dropped the 3 expired pairs, leaving only the just-recorded pair 4");
    }
}
