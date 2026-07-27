package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bukkit非依存の純粋関数テスト(一次情報: 2026-07-25 minecraft.wiki "Mace" / "Density" 確認)。
 */
class MaceSmashDamageTest {

    // --- isSmashAttack: 発動条件 ---

    @Test
    void smashAttackRequiresAtLeast1point5BlocksFallen() {
        assertTrue(MaceSmashDamage.isSmashAttack(1.5, false, false, false));
        assertFalse(MaceSmashDamage.isSmashAttack(1.49, false, false, false));
        assertFalse(MaceSmashDamage.isSmashAttack(0.0, false, false, false));
    }

    @Test
    void smashAttackRequiresNotOnGround() {
        assertFalse(MaceSmashDamage.isSmashAttack(10.0, true, false, false));
        assertTrue(MaceSmashDamage.isSmashAttack(10.0, false, false, false));
    }

    @Test
    void smashAttackRequiresNotGliding() {
        assertFalse(MaceSmashDamage.isSmashAttack(10.0, false, true, false));
    }

    @Test
    void smashAttackRequiresNoSlowFalling() {
        assertFalse(MaceSmashDamage.isSmashAttack(10.0, false, false, true));
    }

    // --- smashTierBonus: バニラ階層式(最初の3ブロック+4/block, 次の5ブロック+2/block, 以降+1/block) ---

    @Test
    void smashTierBonusIsZeroBelowThreshold() {
        assertEquals(0.0, MaceSmashDamage.smashTierBonus(1.0), 1e-9);
        assertEquals(0.0, MaceSmashDamage.smashTierBonus(0.0), 1e-9);
    }

    @Test
    void smashTierBonusAtThreshold() {
        // 1.5ブロック x 4 = 6.0 (すべて最初の階層内)。
        assertEquals(6.0, MaceSmashDamage.smashTierBonus(1.5), 1e-9);
    }

    @Test
    void smashTierBonusExactlyThreeBlocks() {
        assertEquals(12.0, MaceSmashDamage.smashTierBonus(3.0), 1e-9);
    }

    @Test
    void smashTierBonusSpansSecondTier() {
        // 3ブロック*4 + 5ブロック*2 = 12 + 10 = 22 (8ブロック目まで)。
        assertEquals(22.0, MaceSmashDamage.smashTierBonus(8.0), 1e-9);
    }

    @Test
    void smashTierBonusSpansThirdTierUnbounded() {
        // 3*4 + 5*2 + 2*1 = 12 + 10 + 2 = 24 (10ブロック)。
        assertEquals(24.0, MaceSmashDamage.smashTierBonus(10.0), 1e-9);
    }

    @Test
    void smashTierBonusNonFiniteIsZero() {
        assertEquals(0.0, MaceSmashDamage.smashTierBonus(Double.NaN), 1e-9);
    }

    // --- densityBonus: レベル x 0.5 x 落下ブロック(スマッシュ非発動なら0) ---

    @Test
    void densityBonusScalesWithLevelAndFallDistance() {
        assertEquals(10.0, MaceSmashDamage.densityBonus(4.0, 5), 1e-9); // 5 * 0.5 * 4 = 10
        assertEquals(1.25, MaceSmashDamage.densityBonus(2.5, 1), 1e-9); // 1 * 0.5 * 2.5 = 1.25
    }

    @Test
    void densityBonusIsZeroWithoutLevel() {
        assertEquals(0.0, MaceSmashDamage.densityBonus(10.0, 0), 1e-9);
        assertEquals(0.0, MaceSmashDamage.densityBonus(10.0, -1), 1e-9);
    }

    @Test
    void densityBonusIsZeroBelowSmashThresholdEvenWithLevel() {
        assertEquals(0.0, MaceSmashDamage.densityBonus(1.0, 5), 1e-9);
    }

    // --- 二重計上でないことの根拠: スマッシュ階層とDensityは独立した和で、互いを侵食しない ---

    @Test
    void smashTierAndDensityAreIndependentAdditiveTerms() {
        double fallDistance = 5.0;
        int densityLevel = 2;
        double tier = MaceSmashDamage.smashTierBonus(fallDistance); // 3*4 + 2*2 = 16
        double density = MaceSmashDamage.densityBonus(fallDistance, densityLevel); // 2*0.5*5 = 5
        assertEquals(16.0, tier, 1e-9);
        assertEquals(5.0, density, 1e-9);
        // レベル0のときはDensity側だけ0になり、階層式はそのまま(相互汚染しない)。
        assertEquals(tier, MaceSmashDamage.smashTierBonus(fallDistance), 1e-9);
        assertEquals(0.0, MaceSmashDamage.densityBonus(fallDistance, 0), 1e-9);
    }
}
