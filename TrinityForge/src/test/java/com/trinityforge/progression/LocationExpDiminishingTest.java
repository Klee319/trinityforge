package com.trinityforge.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TT/放置対策(2026-07-26)の逓減カーブ本体。Bukkitに触れない純粋関数なので、実際の判定
 * ({@code multiplierAt} の窓刈り・半径判定)とは別にここで数値の性質だけを固定する。
 *
 * <p>ここが狂うと「普通に遊んでいるのにEXPが減る」(しきい値が低すぎ)か「TTが素通り」
 * (下限が高すぎ/減衰が緩すぎ)のどちらかになる。どちらも一目では気付けないので、既定値で
 * 何回目にどこまで落ちるかを明示的に書き留めておく。
 */
class LocationExpDiminishingTest {

    private static final int THRESHOLD = 30;
    private static final double DECAY = 0.05;
    private static final double FLOOR = 0.1;

    @Test
    void belowThresholdIsUntouched() {
        // しきい値までは1.0のまま = 探索しながら戦う普通のプレイは一切罰されない。
        assertEquals(1.0, LocationExpDiminishing.multiplier(0, THRESHOLD, DECAY, FLOOR));
        assertEquals(1.0, LocationExpDiminishing.multiplier(THRESHOLD - 1, THRESHOLD, DECAY, FLOOR));
    }

    @Test
    void firstKillOverThresholdStartsDecaying() {
        // しきい値ちょうど(=既に30回稼いだ状態での31回目)から減り始める。
        assertEquals(0.95, LocationExpDiminishing.multiplier(THRESHOLD, THRESHOLD, DECAY, FLOOR), 1e-9);
        assertEquals(0.90, LocationExpDiminishing.multiplier(THRESHOLD + 1, THRESHOLD, DECAY, FLOOR), 1e-9);
    }

    @Test
    void decayStopsAtFloorAndNeverGoesNegative() {
        // 1 - 18*0.05 = 0.10 ちょうどで下限に到達し、それ以降は何回稼いでも下限のまま。
        assertEquals(FLOOR, LocationExpDiminishing.multiplier(THRESHOLD + 17, THRESHOLD, DECAY, FLOOR), 1e-9);
        assertEquals(FLOOR, LocationExpDiminishing.multiplier(THRESHOLD + 500, THRESHOLD, DECAY, FLOOR), 1e-9);
        assertEquals(FLOOR, LocationExpDiminishing.multiplier(1_000_000, THRESHOLD, DECAY, FLOOR), 1e-9);
    }

    @Test
    void decayIsMonotonicAndStaysInRange() {
        double previous = 1.0;
        for (int count = 0; count <= 200; count++) {
            double value = LocationExpDiminishing.multiplier(count, THRESHOLD, DECAY, FLOOR);
            assertTrue(value <= previous + 1e-9, "倍率は単調非増加であること (count=" + count + ")");
            assertTrue(value >= FLOOR - 1e-9 && value <= 1.0, "倍率は[floor,1.0]に収まること (count=" + count + ")");
            previous = value;
        }
    }

    @Test
    void zeroDecayDisablesTheCurveWithoutDisablingTheFeature() {
        // decay-per-kill: 0 は「数えるが減らさない」= 実質無効化。floorに落ちないことを保証する
        // (floorを掛けてしまうと、減衰0のつもりが一律10%になるという最悪の誤設定になる)。
        assertEquals(1.0, LocationExpDiminishing.multiplier(10_000, THRESHOLD, 0.0, FLOOR), 1e-9);
    }

    @Test
    void thresholdIsReachedOnlyByKillsNotByEveryExpEvent() {
        // 設計の要: カウンタを増やすのは撃破だけ。被弾/命中でも増やしていた頃は、5体を相手に
        // 各6発もらうだけで30件に達し、正常なプレイでEXPが減り始めていた。
        // ここでは「30という数字が撃破回数として妥当か」を人間可読な形で残す:
        // 同じ場所で5分に30体は、探索しながら戦うプレイでは到達しない密度。
        assertEquals(1.0, LocationExpDiminishing.multiplier(29, THRESHOLD, DECAY, FLOOR),
                "5分・半径24ブロックで29体までは無傷であること");
        assertTrue(LocationExpDiminishing.multiplier(48, THRESHOLD, DECAY, FLOOR) <= FLOOR + 1e-9,
                "TT相当(同一地点で49体目)では下限まで落ちていること");
    }

    @Test
    void floorOutsideUnitRangeIsClamped() {
        // 設定ミス(負値や1超)でも倍率が[0,1]から出ないこと。
        assertEquals(1.0, LocationExpDiminishing.multiplier(THRESHOLD + 100, THRESHOLD, DECAY, 5.0), 1e-9);
        assertTrue(LocationExpDiminishing.multiplier(THRESHOLD + 100, THRESHOLD, DECAY, -1.0) >= 0.0);
    }
}
