package com.trinityforge.active;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CooldownManager}: per-(player, skillId) check-and-consume + remaining-time + quit-cleanup. */
class CooldownManagerTest {

    private final UUID player = UUID.randomUUID();

    @Test
    void firstUseAlwaysConsumes() {
        CooldownManager manager = new CooldownManager();
        assertTrue(manager.tryConsume(player, "haste-active-mining", 1000L, 0L));
    }

    @Test
    void secondUseBeforeCooldownElapsedIsRefused() {
        CooldownManager manager = new CooldownManager();
        assertTrue(manager.tryConsume(player, "skill", 1000L, 0L));
        assertFalse(manager.tryConsume(player, "skill", 1000L, 500L));
    }

    @Test
    void secondUseAfterCooldownElapsedIsConsumed() {
        CooldownManager manager = new CooldownManager();
        assertTrue(manager.tryConsume(player, "skill", 1000L, 0L));
        assertTrue(manager.tryConsume(player, "skill", 1000L, 1000L));
    }

    @Test
    void differentSkillsHaveIndependentCooldowns() {
        CooldownManager manager = new CooldownManager();
        assertTrue(manager.tryConsume(player, "skill-a", 1000L, 0L));
        assertTrue(manager.tryConsume(player, "skill-b", 1000L, 0L));
    }

    @Test
    void differentPlayersHaveIndependentCooldowns() {
        CooldownManager manager = new CooldownManager();
        UUID otherPlayer = UUID.randomUUID();
        assertTrue(manager.tryConsume(player, "skill", 1000L, 0L));
        assertTrue(manager.tryConsume(otherPlayer, "skill", 1000L, 0L));
    }

    @Test
    void remainingMillisIsZeroWhenNeverUsed() {
        CooldownManager manager = new CooldownManager();
        assertEquals(0L, manager.remainingMillis(player, "skill", 1000L, 0L));
    }

    @Test
    void remainingMillisCountsDownAndNeverGoesNegative() {
        CooldownManager manager = new CooldownManager();
        manager.tryConsume(player, "skill", 1000L, 0L);
        assertEquals(600L, manager.remainingMillis(player, "skill", 1000L, 400L));
        assertEquals(0L, manager.remainingMillis(player, "skill", 1000L, 5000L));
    }

    @Test
    void clearDropsEveryCooldownForThatPlayer() {
        CooldownManager manager = new CooldownManager();
        manager.tryConsume(player, "skill", 1000L, 0L);
        manager.clear(player);
        assertTrue(manager.tryConsume(player, "skill", 1000L, 100L));
    }

    @Test
    void failedConsumeDoesNotResetTheOriginalLastUseTimestamp() {
        CooldownManager manager = new CooldownManager();
        manager.tryConsume(player, "skill", 1000L, 0L);
        manager.tryConsume(player, "skill", 1000L, 300L); // refused, must not shift the clock
        assertEquals(700L, manager.remainingMillis(player, "skill", 1000L, 300L));
    }

    // --- applyReduction: shared スキルCT短縮 clamp (2026-07-25 CT短縮ステータス分離 §1-B) ---

    @Test
    void applyReductionShrinksCooldownForPositiveFraction() {
        // 50% reduction: multiplier = max(0.05, 1.0 - min(0.9, 0.5)) = 0.5.
        assertEquals(500L, CooldownManager.applyReduction(1000L, 0.5));
    }

    @Test
    void applyReductionCapsAt90PercentReduction() {
        // Anything >= 0.9 clamps to the same floor (10% of the original length), matching
        // CombatListener.startItemCooldown's cooldown-reduction clamp shape.
        assertEquals(100L, CooldownManager.applyReduction(1000L, 0.9));
        assertEquals(100L, CooldownManager.applyReduction(1000L, 5.0));
    }

    @Test
    void applyReductionIncreasesCooldownForNegativeFraction() {
        // -50% ("CT増加" per ユーザー要望): multiplier = 1.0 - (-0.5) = 1.5.
        assertEquals(1500L, CooldownManager.applyReduction(1000L, -0.5));
    }

    @Test
    void applyReductionZeroFractionLeavesCooldownUnchanged() {
        assertEquals(1000L, CooldownManager.applyReduction(1000L, 0.0));
    }

    @Test
    void applyReductionIgnoresNonFiniteFraction() {
        assertEquals(1000L, CooldownManager.applyReduction(1000L, Double.NaN));
        assertEquals(1000L, CooldownManager.applyReduction(1000L, Double.POSITIVE_INFINITY));
    }

    @Test
    void applyReductionNeverProducesNegativeOrGoesBelowZeroBaseMillis() {
        assertEquals(0L, CooldownManager.applyReduction(0L, 0.9));
        assertEquals(0L, CooldownManager.applyReduction(-100L, 0.5));
    }
}
