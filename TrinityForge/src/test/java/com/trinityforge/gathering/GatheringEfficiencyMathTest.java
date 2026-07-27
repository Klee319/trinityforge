package com.trinityforge.gathering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link GatheringEfficiencyMath#resolveLevel} floor+clamp arithmetic, no Bukkit involved. */
class GatheringEfficiencyMathTest {

    @Test
    void floorsFractionalTotalDownToWholeLevel() {
        assertEquals(3, GatheringEfficiencyMath.resolveLevel(3.9, 5));
    }

    @Test
    void zeroOrNegativeTotalGrantsNoLevel() {
        assertEquals(0, GatheringEfficiencyMath.resolveLevel(0.0, 5));
        assertEquals(0, GatheringEfficiencyMath.resolveLevel(-4.0, 5));
    }

    @Test
    void clampsToConfiguredMaxLevel() {
        assertEquals(5, GatheringEfficiencyMath.resolveLevel(11.0, 5));
    }

    @Test
    void customMaxLevelIsRespected() {
        assertEquals(2, GatheringEfficiencyMath.resolveLevel(7.0, 2));
    }

    @Test
    void nonFiniteTotalGrantsNoLevel() {
        assertEquals(0, GatheringEfficiencyMath.resolveLevel(Double.NaN, 5));
        assertEquals(0, GatheringEfficiencyMath.resolveLevel(Double.POSITIVE_INFINITY, 5));
    }

    @Test
    void exactWholeNumberTotalIsNotAffectedByFlooring() {
        assertEquals(4, GatheringEfficiencyMath.resolveLevel(4.0, 5));
    }

    // ------------------------------------------------------------------------
    // 2026-07-26 ユーザー決定: 上限撤廃(max-level <= 0 は無制限、内部ハード上限255だけは残す)。
    // ------------------------------------------------------------------------

    @Test
    void zeroMaxLevelMeansUnlimited() {
        // 撤廃前は maxLevel=0 は「上限0」= 常に0だった。撤廃後は「無制限」を意味するので、
        // ハード上限(255)未満なら floor(total) がそのまま通る。
        assertEquals(42, GatheringEfficiencyMath.resolveLevel(42.0, 0));
    }

    @Test
    void negativeMaxLevelAlsoMeansUnlimited() {
        // 撤廃前は負のmaxLevelは「上限0」に丸められ常に0だった。撤廃後は0と同じ「無制限」扱いになる。
        assertEquals(4, GatheringEfficiencyMath.resolveLevel(4.0, -1));
        assertEquals(100, GatheringEfficiencyMath.resolveLevel(100.0, -5));
    }

    @Test
    void unlimitedIsStillClampedToTheInternalHardCap() {
        // 無制限(maxLevel<=0)でも、NBT short安全のための内部ハード上限255は超えない。
        assertEquals(255, GatheringEfficiencyMath.resolveLevel(9999.0, 0));
        assertEquals(255, GatheringEfficiencyMath.resolveLevel(300.0, -1));
    }

    @Test
    void positiveMaxLevelStillActsAsATraditionalCeiling() {
        // 正の値は従来どおり上限として機能する(後方互換)。
        assertEquals(3, GatheringEfficiencyMath.resolveLevel(10.0, 3));
    }

    @Test
    void positiveMaxLevelAboveTheHardCapIsClampedToTheHardCap() {
        // 設定ミスで255超の正の上限を書いても、内部ハード上限255を超えない。
        assertEquals(255, GatheringEfficiencyMath.resolveLevel(9999.0, 1000));
    }
}
