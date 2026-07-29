package com.trinityforge.durability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 装備耐久ペナルティの純粋計算(2026-07-30)。 */
class DurabilityPenaltyTest {

    @Test
    void amountUsesMinWhenPercentRoundsToZero() {
        // ダイヤ胸当て(最大耐久528) × 0.1% = 0.528 → 切り捨て0 → 下限1が効く。
        assertEquals(1, DurabilityPenalty.amountFor(528, 0.001, 1));
        // 下限0なら「割合が1点に届かない装備は減らない」設定も作れる。
        assertEquals(0, DurabilityPenalty.amountFor(528, 0.001, 0));
    }

    @Test
    void amountScalesWithPercentAndNeverExceedsMax() {
        assertEquals(52, DurabilityPenalty.amountFor(528, 0.1, 1), "死亡ペナルティ10% = 52点");
        assertEquals(528, DurabilityPenalty.amountFor(528, 2.0, 1), "割合が1超でも最大耐久で止める");
    }

    @Test
    void amountIsZeroForItemsWithoutDurability() {
        assertEquals(0, DurabilityPenalty.amountFor(0, 0.1, 1));
        assertEquals(0, DurabilityPenalty.amountFor(528, 0.0, 1));
        assertEquals(0, DurabilityPenalty.amountFor(528, Double.NaN, 1));
    }

    @Test
    void unbreakingKeepsVanillaExpectationForSinglePointDamage() {
        // 1点・耐久力III: 決定的に切り捨てると常に0(=耐久力IIIでペナルティ完全無効)になるため、
        // 端数は確率で扱う。scaled = 0.25 なので roll<0.25 のときだけ1点減る。
        assertEquals(1, DurabilityPenalty.afterUnbreaking(1, 3, 0.24));
        assertEquals(0, DurabilityPenalty.afterUnbreaking(1, 3, 0.25));
        assertEquals(1, DurabilityPenalty.afterUnbreaking(1, 0, 0.99), "エンチャント無しはそのまま");
    }

    @Test
    void unbreakingScalesLargePenalties() {
        // 52点・耐久力I → 26点(端数なし)。
        assertEquals(26, DurabilityPenalty.afterUnbreaking(52, 1, 0.99));
        // 52点・耐久力III → 13点(端数なし)。
        assertEquals(13, DurabilityPenalty.afterUnbreaking(52, 3, 0.99));
    }

    @Test
    void preventBreakStopsAtOneDurabilityLeft() {
        DurabilityPenalty.Result result = DurabilityPenalty.apply(500, 100, 528, true);
        assertEquals(527, result.damage(), "残耐久1で止まる");
        assertFalse(result.broken());
    }

    @Test
    void preventBreakNeverRestoresDurability() {
        // すでに残耐久1(damage=527)の装備をさらに殴っても damage が減る方向へは動かさない。
        DurabilityPenalty.Result result = DurabilityPenalty.apply(527, 100, 528, true);
        assertEquals(527, result.damage());
        assertFalse(result.broken());
    }

    @Test
    void breakingAllowedWhenPreventBreakDisabled() {
        DurabilityPenalty.Result result = DurabilityPenalty.apply(500, 100, 528, false);
        assertTrue(result.broken());
    }

    @Test
    void normalApplicationJustAddsDamage() {
        DurabilityPenalty.Result result = DurabilityPenalty.apply(10, 52, 528, true);
        assertEquals(62, result.damage());
        assertFalse(result.broken());
    }
}
