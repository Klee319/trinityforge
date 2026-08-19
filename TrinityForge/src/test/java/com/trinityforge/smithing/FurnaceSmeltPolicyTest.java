package com.trinityforge.smithing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FurnaceSmeltPolicyTest {

    @Test
    void effectivePercentIsUnchangedForManual() {
        assertEquals(30.0, FurnaceSmeltPolicy.effectivePercent(30.0, false, 0.25), 1e-9);
    }

    @Test
    void effectivePercentIsDecayedForAutomated() {
        assertEquals(7.5, FurnaceSmeltPolicy.effectivePercent(30.0, true, 0.25), 1e-9);
    }

    @Test
    void effectivePercentZeroWhenAutoMultiplierIsZero() {
        assertEquals(0.0, FurnaceSmeltPolicy.effectivePercent(30.0, true, 0.0), 1e-9);
    }

    @Test
    void effectivePercentNonPositiveRawYieldsZero() {
        assertEquals(0.0, FurnaceSmeltPolicy.effectivePercent(0.0, false, 1.0), 1e-9);
        assertEquals(0.0, FurnaceSmeltPolicy.effectivePercent(-5.0, false, 1.0), 1e-9);
        assertEquals(0.0, FurnaceSmeltPolicy.effectivePercent(Double.NaN, false, 1.0), 1e-9);
    }

    /**
     * W-150: 出荷 tier の「速度+X%」が実時間へどう落ちるかを固定する。
     * かまどのバニラ基準は 200 tick(10秒/個)なので、1スタック(64個)の所要は tick*64/20 秒。
     */
    @Test
    void cookTimeWithSpeedBonusMatchesShippedTiers() {
        assertEquals(160, FurnaceSmeltPolicy.cookTimeWithSpeedBonus(200, 25.0)); // 1.25倍速 = 8.0s/個
        assertEquals(114, FurnaceSmeltPolicy.cookTimeWithSpeedBonus(200, 75.0)); // 1.75倍速 = 5.7s/個
        assertEquals(74, FurnaceSmeltPolicy.cookTimeWithSpeedBonus(200, 170.0)); // 2.7倍速 = 3.7s/個
    }

    /**
     * 旧「短縮%」解釈では tier3 の 100 が 1 tick(= 1スタック3.2秒)へ落ちて実サーバ報告の
     * 症状になっていた。速度解釈では 2 倍速 = 100 tick になることを縛る。
     */
    @Test
    void cookTimeWithSpeedBonusTreatsHundredPercentAsDoubleSpeed() {
        assertEquals(100, FurnaceSmeltPolicy.cookTimeWithSpeedBonus(200, 100.0));
    }

    @Test
    void cookTimeWithSpeedBonusZeroPercentKeepsBase() {
        assertEquals(200, FurnaceSmeltPolicy.cookTimeWithSpeedBonus(200, 0.0));
        assertEquals(200, FurnaceSmeltPolicy.cookTimeWithSpeedBonus(200, -30.0));
        assertEquals(200, FurnaceSmeltPolicy.cookTimeWithSpeedBonus(200, Double.NaN));
    }

    @Test
    void cookTimeWithSpeedBonusClampsToAtLeastOneTick() {
        assertEquals(1, FurnaceSmeltPolicy.cookTimeWithSpeedBonus(200, 1_000_000.0));
        assertEquals(1, FurnaceSmeltPolicy.cookTimeWithSpeedBonus(1, 99.0));
    }

    @Test
    void cookTimeWithSpeedBonusNonPositiveBaseUnchanged() {
        assertEquals(0, FurnaceSmeltPolicy.cookTimeWithSpeedBonus(0, 170.0));
        assertEquals(-5, FurnaceSmeltPolicy.cookTimeWithSpeedBonus(-5, 170.0));
    }
}
