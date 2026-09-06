package com.trinityforge.integration.ars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 儀式のソース由来EXPに天井が効くこと(2026-09-04)。
 *
 * <p>実機で ARS_SMITHING の累計EXPが必要量の百万倍規模まで膨らみ、プレステージ直後に
 * 数秒で最大レベルへ戻っていた。原因は「消費ソース量 × 係数」に上限が無かったこと。
 * 上位階梯のソース要求量は 4,500 万に達するので、係数が小さくても積が最大レベルぶんを超える。
 */
class ArsSourceExpCapTest {

    /** Lv100 到達に必要な累計EXPの実測値(実サーバDBの total/residual から逆算)。 */
    private static final double LEVEL_100_TOTAL = 7_212_359.0;

    @Test
    @DisplayName("天井なしだと最上位の儀式1回で最大レベルぶんを超える(修正前の挙動)")
    void withoutCapTopTierRitualExceedsFullSkill() {
        double raw = ArsProgressionBridge.sourceExp(45_000_000, 0.2, 0.0);
        assertEquals(9_000_000.0, raw);
        assertTrue(raw > LEVEL_100_TOTAL,
                "天井が無ければ儀式1回で Lv100 の必要累計を超えてしまう");
    }

    @Test
    @DisplayName("天井ありなら最上位の儀式でも頭打ちになる")
    void capClampsTopTierRitual() {
        double capped = ArsProgressionBridge.sourceExp(45_000_000, 0.2, 100_000.0);
        assertEquals(100_000.0, capped);
        assertTrue(capped < LEVEL_100_TOTAL);
    }

    @Test
    @DisplayName("下位の儀式は天井に届かないので今までどおり")
    void lowTierRitualIsUnaffected() {
        assertEquals(20.0, ArsProgressionBridge.sourceExp(100, 0.2, 100_000.0));
        assertEquals(2_000.0, ArsProgressionBridge.sourceExp(10_000, 0.2, 100_000.0));
    }

    @Test
    @DisplayName("天井 0 と非有限は上限なし。既存の2引数版も上限なしのまま")
    void zeroOrNonFiniteCapMeansUnlimited() {
        assertEquals(9_000_000.0, ArsProgressionBridge.sourceExp(45_000_000, 0.2, 0.0));
        assertEquals(9_000_000.0, ArsProgressionBridge.sourceExp(45_000_000, 0.2, Double.NaN));
        assertEquals(9_000_000.0, ArsProgressionBridge.sourceExp(45_000_000, 0.2));
    }

    @Test
    @DisplayName("消費ゼロ・負の係数はこれまでどおり 0")
    void guardsStillReturnZero() {
        assertEquals(0.0, ArsProgressionBridge.sourceExp(0, 0.2, 100_000.0));
        assertEquals(0.0, ArsProgressionBridge.sourceExp(-5, 0.2, 100_000.0));
        assertEquals(0.0, ArsProgressionBridge.sourceExp(100, -0.2, 100_000.0));
    }
}
