package com.trinityforge.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FinalDamageScaling#scaleFactor} の純関数契約(2026-07-31 F5 指摘1)。
 * Bukkit を要求しないので必ず実行される(モックの既定値に依存しない)。
 */
class FinalDamageScalingTest {

    @Test
    @DisplayName("最終ダメージを目標値へ移す係数を返す")
    void scalesFinalDamageDownToTheTarget() {
        assertEquals(3.0 / 7640.0, FinalDamageScaling.scaleFactor(7640.0, 3.0), 1e-12);
        assertEquals(0.5, FinalDamageScaling.scaleFactor(10.0, 5.0), 1e-12);
    }

    @Test
    @DisplayName("縮める余地が無い入力では必ず 1.0(呼び出し側が早期returnできる契約)")
    void returnsOneWhenNothingToScale() {
        assertEquals(1.0, FinalDamageScaling.scaleFactor(0.0, 3.0), 1e-12, "最終が0");
        assertEquals(1.0, FinalDamageScaling.scaleFactor(-5.0, 3.0), 1e-12, "最終が負(回復側)");
        assertEquals(1.0, FinalDamageScaling.scaleFactor(10.0, 10.0), 1e-12, "抑制なし");
        assertEquals(1.0, FinalDamageScaling.scaleFactor(10.0, 25.0), 1e-12,
                "増幅方向はPvP抑制の役目ではないので素通し");
        assertEquals(1.0, FinalDamageScaling.scaleFactor(Double.NaN, 3.0), 1e-12);
        assertEquals(1.0, FinalDamageScaling.scaleFactor(Double.POSITIVE_INFINITY, 3.0), 1e-12);
        assertEquals(1.0, FinalDamageScaling.scaleFactor(10.0, Double.NaN), 1e-12);
    }

    @Test
    @DisplayName("目標が負なら0(=最終ダメージ0)へ寄せる。負の最終ダメージは作らない")
    void negativeTargetClampsToZero() {
        assertEquals(0.0, FinalDamageScaling.scaleFactor(10.0, -3.0), 1e-12);
    }
}
