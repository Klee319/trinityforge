package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobDropRollerTest {

    @Test
    void chanceZeroNeverRolls() {
        assertFalse(MobDropRoller.rolls(0.0, 0.0));
        assertFalse(MobDropRoller.rolls(0.0, 0.5));
    }

    @Test
    void chanceOneAlwaysRolls() {
        assertTrue(MobDropRoller.rolls(1.0, 0.0));
        assertTrue(MobDropRoller.rolls(1.0, 0.9999));
    }

    @Test
    void midChanceRollsBelowThresholdOnly() {
        assertTrue(MobDropRoller.rolls(0.5, 0.4));
        assertFalse(MobDropRoller.rolls(0.5, 0.5));
        assertFalse(MobDropRoller.rolls(0.5, 0.6));
    }

    @Test
    void rollCountEqualMinMaxAlwaysReturnsThatValue() {
        assertEquals(3, MobDropRoller.rollCount(3, 3, 0));
        assertEquals(3, MobDropRoller.rollCount(3, 3, 12345));
        assertEquals(3, MobDropRoller.rollCount(3, 3, -999));
    }

    @Test
    void rollCountStaysWithinInclusiveRange() {
        for (int sample = -20; sample <= 20; sample++) {
            int count = MobDropRoller.rollCount(1, 4, sample);
            assertTrue(count >= 1 && count <= 4, "count=" + count + " out of [1,4] for sample=" + sample);
        }
    }

    @Test
    void rollCountBoundaryRandomInputsHitBothEnds() {
        assertEquals(1, MobDropRoller.rollCount(1, 4, 0));
        assertEquals(4, MobDropRoller.rollCount(1, 4, 3));
    }

    @Test
    void rollCountHandlesNegativeRandomInput() {
        int count = MobDropRoller.rollCount(2, 5, -1);
        assertTrue(count >= 2 && count <= 5);
    }

    @Test
    void rollCountDoesNotOverflowAtIntMaxRange() {
        // min=0, max=Integer.MAX_VALUE previously computed range = max - min + 1 in int
        // arithmetic, wrapping to Integer.MIN_VALUE (a negative modulus). The fix computes the
        // range as a long and additionally clamps max to a sane stack-size bound.
        int count = MobDropRoller.rollCount(0, Integer.MAX_VALUE, 12345);
        assertTrue(count >= 0, "count=" + count + " must not be negative after overflow fix");
    }

    @Test
    void rollCountClampsAbsurdMaxToSafeStackBound() {
        int count = MobDropRoller.rollCount(0, Integer.MAX_VALUE, 0);
        assertTrue(count <= 1_000_000, "count=" + count + " should be bounded to a sane stack size");
    }

    @Test
    void rollCountStaysSaneWhenMinAloneExceedsBound() {
        // Pathological config: min itself is huge. Range must never go negative/zero-divide.
        int count = MobDropRoller.rollCount(2_000_000, Integer.MAX_VALUE, 7);
        assertTrue(count >= 2_000_000, "count=" + count + " must be >= min");
    }

    // --- 2026-08-13 ドロップ増加ステの棲み分け(レアドロップ=確率 / それ以外=個数) ---

    @Test
    void isSingleFixedOnlyForExactlyOneItem() {
        assertTrue(MobDropRoller.isSingleFixed(1, 1), "1個固定＝レアドロップ扱い");
        assertFalse(MobDropRoller.isSingleFixed(1, 2), "1〜2個はランダム＝個数を足す側");
        assertFalse(MobDropRoller.isSingleFixed(2, 2), "2個固定も個数を足す側");
        assertFalse(MobDropRoller.isSingleFixed(0, 1), "0〜1個もランダム＝個数を足す側");
    }

    @Test
    void clampBonusRejectsNegativeAndCapsAtTwoHundredPercent() {
        assertEquals(0.0, MobDropRoller.clampBonus(-0.5));
        assertEquals(0.5, MobDropRoller.clampBonus(0.5));
        assertEquals(2.0, MobDropRoller.clampBonus(2.0));
        assertEquals(2.0, MobDropRoller.clampBonus(9.0), "上限は+200%");
        assertEquals(0.0, MobDropRoller.clampBonus(Double.NaN), "壊れた値は無効化する");
    }

    /** ユーザー指示の例: 1%で1つ落ちるアイテムに+100%を盛ると2%になる。 */
    @Test
    void boostedChanceRaisesRareDropRate() {
        assertEquals(0.02, MobDropRoller.boostedChance(0.01, 1.0), 1e-9);
        assertEquals(0.015, MobDropRoller.boostedChance(0.01, 0.5), 1e-9);
        assertEquals(0.01, MobDropRoller.boostedChance(0.01, 0.0), 1e-9, "ボーナス0なら素通し");
    }

    @Test
    void boostedChanceIsCappedAtOneAndKeepsZeroAtZero() {
        assertEquals(1.0, MobDropRoller.boostedChance(0.8, 1.0), 1e-9, "ドロップ率なので100%で頭打ち");
        assertEquals(1.0, MobDropRoller.boostedChance(1.0, 2.0), 1e-9);
        assertEquals(0.0, MobDropRoller.boostedChance(0.0, 2.0), 1e-9,
                "落ちない設定のものを落ちるようにはしない");
    }

    /** ユーザー指示の例: +50%なら50%の確率で1つ増える。 */
    @Test
    void extraCountRollsTheFractionalPart() {
        assertEquals(1, MobDropRoller.extraCount(0.5, 0.49));
        assertEquals(0, MobDropRoller.extraCount(0.5, 0.5), "境界は外れ(strict <)");
        assertEquals(0, MobDropRoller.extraCount(0.5, 0.99));
        assertEquals(0, MobDropRoller.extraCount(0.0, 0.0), "ボーナス0なら絶対に増えない");
    }

    /** ユーザー指示の例: 100%を超えたら確定で1つ増え、超過分の確率でさらに1つ。 */
    @Test
    void extraCountGivesWholePartUnconditionally() {
        assertEquals(1, MobDropRoller.extraCount(1.0, 0.99), "+100%は乱数に関係なく確定+1");
        assertEquals(2, MobDropRoller.extraCount(1.5, 0.49), "+150%は確定+1、超過50%を当てて+1");
        assertEquals(1, MobDropRoller.extraCount(1.5, 0.51), "超過分を外したら+1止まり");
        assertEquals(2, MobDropRoller.extraCount(5.0, 0.99), "クランプ後の+200%が上限なので+2まで");
    }

    @Test
    void cappedCountKeepsStackBoundsUnchanged() {
        assertEquals(33, MobDropRoller.cappedCount(33, 64));
        assertEquals(512, MobDropRoller.cappedCount(1000, 64), "上限は maxStackSize×8");
        assertEquals(8, MobDropRoller.cappedCount(1000, 1), "最大スタック1のツールでも×8");
        assertEquals(1, MobDropRoller.cappedCount(0, 64), "0や負を返さない");
    }
}
