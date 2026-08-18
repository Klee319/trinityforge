package com.trinityforge.listeners;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 醸造診断の「出す/出さない」判定(2026-08-19 / W-112)。
 *
 * <p>ここが緩いと正常な醸造まで WARNING を吐いてログが埋まり、厳しすぎると
 * 肝心の「変換されなかった醸造」を取りこぼす。実サーバで1回しか再現機会が無い種類の
 * 調査なので、判定そのものをテストで固定する。
 */
class BrewDiagnosticListenerTest {

    private static final List<String> THREE_WATER = List.of(
            "POTIONx1{base=WATER,effects=0}",
            "POTIONx1{base=WATER,effects=0}",
            "POTIONx1{base=WATER,effects=0}");

    @Test
    @DisplayName("キャンセルされた醸造は必ず出す")
    void cancelledIsAlwaysReported() {
        assertTrue(BrewDiagnosticListener.isSuspicious(true, THREE_WATER, THREE_WATER));
        // 変換されていてもキャンセルされたなら出す(誰が止めたかの手掛かりが最優先)。
        List<String> awkward = List.of(
                "POTIONx1{base=AWKWARD,effects=0}",
                "POTIONx1{base=AWKWARD,effects=0}",
                "POTIONx1{base=AWKWARD,effects=0}");
        assertTrue(BrewDiagnosticListener.isSuspicious(true, THREE_WATER, awkward));
    }

    @Test
    @DisplayName("入力と出力が同じまま(=何も変換されていない)なら出す")
    void unchangedBottlesAreReported() {
        assertTrue(BrewDiagnosticListener.isSuspicious(false, THREE_WATER, THREE_WATER),
                "W-112 の報告そのもの: 水入り瓶が水入り瓶のまま完成する");
    }

    @Test
    @DisplayName("水→奇妙のような正常な変換では出さない")
    void normalBrewIsSilent() {
        List<String> awkward = List.of(
                "POTIONx1{base=AWKWARD,effects=0}",
                "POTIONx1{base=AWKWARD,effects=0}",
                "POTIONx1{base=AWKWARD,effects=0}");
        assertFalse(BrewDiagnosticListener.isSuspicious(false, THREE_WATER, awkward));
    }

    @Test
    @DisplayName("1本でも変換されていれば正常扱いにする(1枠だけ空/別種を混ぜた台で誤検知しない)")
    void partialConversionIsSilent() {
        List<String> inputs = List.of(
                "POTIONx1{base=WATER,effects=0}",
                "empty",
                "POTIONx1{base=WATER,effects=0}");
        List<String> outputs = List.of(
                "POTIONx1{base=AWKWARD,effects=0}",
                "empty",
                "POTIONx1{base=WATER,effects=0}");
        assertFalse(BrewDiagnosticListener.isSuspicious(false, inputs, outputs));
    }

    @Test
    @DisplayName("ビンが1本も無い醸造では出さない(空の台で毎tick鳴らさない)")
    void emptyStandIsSilent() {
        List<String> empty = List.of("empty", "empty", "empty");
        assertFalse(BrewDiagnosticListener.isSuspicious(false, empty, empty));
    }

    @Test
    @DisplayName("素材の記述は比較対象に含めない(inputs の末尾にある素材で誤判定しない)")
    void ingredientEntryIsNotCompared() {
        // atLowest は inputs の末尾に "ingredient=..." を足す。比較は 0..2 のビン枠だけ。
        List<String> inputs = List.of(
                "POTIONx1{base=WATER,effects=0}",
                "POTIONx1{base=WATER,effects=0}",
                "POTIONx1{base=WATER,effects=0}",
                "ingredient=NETHER_WARTx1");
        List<String> outputs = List.of(
                "POTIONx1{base=AWKWARD,effects=0}",
                "POTIONx1{base=AWKWARD,effects=0}",
                "POTIONx1{base=AWKWARD,effects=0}");
        assertFalse(BrewDiagnosticListener.isSuspicious(false, inputs, outputs));
    }

    @Test
    @DisplayName("null/AIR は empty として記述する")
    void nullDescribesAsEmpty() {
        assertTrue("empty".equals(BrewDiagnosticListener.describe(null)));
    }
}
