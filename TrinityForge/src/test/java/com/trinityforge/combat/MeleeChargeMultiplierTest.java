package com.trinityforge.combat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2 (2026-07-25 バグ報告 / 2026-07-25 レビュー修正): 近接チャージ倍率の純粋関数境界テスト。
 * バニラ式 {@code 0.2 + t^2 * 0.8} を一般化した {@link MeleeChargeMultiplier#compute}。
 *
 * <p>レビュー修正(HIGH指摘1・3): {@code Player#getAttackCooldown()} のスタブには一切依存しない。
 * 入力は「経過tick数」と「攻撃速度(攻撃/秒)」という純粋な数値のみ——{@link #compute} と
 * {@link MeleeChargeMultiplier#chargeFraction} の実チャージ換算式(ticksForFullCharge = 20/attackSpeed)
 * を直接検証するので、「Bukkitが実際に何を返すか」ではなく「TF自身の計算が正しいか」を保証する。
 */
class MeleeChargeMultiplierTest {

    private static final double DEFAULT_SPEED = 4.0; // vanilla既定 Attribute.ATTACK_SPEED

    /** 既定攻撃速度4.0での「フルチャージに必要なtick数」= 20/4.0 = 5 tick。 */
    private static final int TICKS_FOR_FULL_CHARGE_AT_DEFAULT_SPEED = 5;

    @Test
    void zeroElapsedTicksYieldsMinMultiplier() {
        assertEquals(0.2, MeleeChargeMultiplier.compute(true, 0, DEFAULT_SPEED, 0.2, 2.0), 1e-9);
    }

    @Test
    void fullyChargedElapsedYieldsFullMultiplier() {
        assertEquals(1.0, MeleeChargeMultiplier.compute(
                true, TICKS_FOR_FULL_CHARGE_AT_DEFAULT_SPEED, DEFAULT_SPEED, 0.2, 2.0), 1e-9);
    }

    @Test
    void neverAttackedSentinelIsTreatedAsFullyCharged() {
        assertEquals(1.0, MeleeChargeMultiplier.compute(
                true, MeleeChargeTracker.NEVER_ATTACKED, DEFAULT_SPEED, 0.2, 2.0), 1e-9);
    }

    @Test
    void elapsedBeyondFullChargeIsClampedNotOverCharged() {
        // 2倍以上待っても倍率は1.0を超えない(上限クランプ)。
        assertEquals(1.0, MeleeChargeMultiplier.compute(
                true, TICKS_FOR_FULL_CHARGE_AT_DEFAULT_SPEED * 10, DEFAULT_SPEED, 0.2, 2.0), 1e-9);
    }

    @Test
    void chargeFractionMatchesElapsedOverTicksForFullCharge() {
        // attackSpeed=4.0(既定) -> ticksForFullCharge=5tick。3/5tick = 0.6。
        assertEquals(0.6, MeleeChargeMultiplier.chargeFraction(3, DEFAULT_SPEED), 1e-9);
    }

    @ParameterizedTest
    @CsvSource({
        // attackSpeed=1.0 -> ticksForFullCharge=20tick。端数の無いtick刻みでバニラ式を厳密比較する。
        "0,  1.0, 0.2",
        "5,  1.0, 0.25",   // t=0.25 -> 0.2 + 0.25^2*0.8 = 0.25
        "10, 1.0, 0.4",    // t=0.5  -> 0.2 + 0.5^2 *0.8 = 0.4
        "15, 1.0, 0.65",   // t=0.75 -> 0.2 + 0.75^2*0.8 = 0.65
        "20, 1.0, 1.0",    // t=1.0  -> フルチャージ
    })
    void matchesVanillaFormulaAcrossRange(int elapsed, double speed, double expected) {
        assertEquals(expected, MeleeChargeMultiplier.compute(true, elapsed, speed, 0.2, 2.0), 1e-9);
    }

    @Test
    void disabledTogglesAlwaysReturnsOneRegardlessOfCharge() {
        assertEquals(1.0, MeleeChargeMultiplier.compute(false, 0, DEFAULT_SPEED, 0.2, 2.0), 1e-9);
        assertEquals(1.0, MeleeChargeMultiplier.compute(
                false, TICKS_FOR_FULL_CHARGE_AT_DEFAULT_SPEED, DEFAULT_SPEED, 0.2, 2.0), 1e-9);
        assertEquals(1.0, MeleeChargeMultiplier.compute(
                false, MeleeChargeTracker.NEVER_ATTACKED, DEFAULT_SPEED, 0.2, 2.0), 1e-9);
    }

    @Test
    void negativeElapsedTicksIsClampedToZero() {
        assertEquals(0.2, MeleeChargeMultiplier.compute(true, -5, DEFAULT_SPEED, 0.2, 2.0), 1e-9);
    }

    @Test
    void nonFiniteOrNonPositiveAttackSpeedFallsBackToVanillaDefault() {
        // attackSpeed<=0/非有限 -> 既定4.0にフォールバック -> ticksForFullCharge=5。elapsed=5 -> t=1.0。
        assertEquals(1.0, MeleeChargeMultiplier.compute(true, 5, 0.0, 0.2, 2.0), 1e-9);
        assertEquals(1.0, MeleeChargeMultiplier.compute(true, 5, -1.0, 0.2, 2.0), 1e-9);
        assertEquals(1.0, MeleeChargeMultiplier.compute(true, 5, Double.NaN, 0.2, 2.0), 1e-9);
        assertEquals(1.0, MeleeChargeMultiplier.compute(true, 5, Double.POSITIVE_INFINITY, 0.2, 2.0), 1e-9);
    }

    @Test
    void extremeAttackSpeedStillProducesClampedFraction() {
        // 極端に速い攻撃速度(例: 100/秒) -> ticksForFullCharge=0.2tick未満 -> 1tickでも即フルチャージ。
        assertEquals(1.0, MeleeChargeMultiplier.compute(true, 1, 100.0, 0.2, 2.0), 1e-9);
        // 極端に遅い攻撃速度(例: 0.01/秒) -> ticksForFullCharge=2000tick -> 1tickでは実質未チャージ。
        double result = MeleeChargeMultiplier.compute(true, 1, 0.01, 0.2, 2.0);
        assertTrue(result < 0.21, "slow attack speed should leave the multiplier near the floor: " + result);
    }

    @Test
    void nonFiniteMinMultiplierFallsBackToVanillaDefault() {
        // min=NaN -> 0.2 default; elapsed=0 -> the floor itself.
        assertEquals(0.2, MeleeChargeMultiplier.compute(true, 0, DEFAULT_SPEED, Double.NaN, 2.0), 1e-9);
    }

    @Test
    void nonPositiveExponentFallsBackToVanillaDefault() {
        // exponent<=0 -> 2.0 default; elapsed=10,speed=1.0 (t=0.5) with default exponent 2 -> 0.4.
        assertEquals(0.4, MeleeChargeMultiplier.compute(true, 10, 1.0, 0.2, 0.0), 1e-9);
        assertEquals(0.4, MeleeChargeMultiplier.compute(true, 10, 1.0, 0.2, -3.0), 1e-9);
    }

    /**
     * 【2026-08-01】バランス調整(要件1a): 出荷 combat/damage.yml の新既定値
     * (min-multiplier=0.1, exponent=1.6) を代表チャージ率(20/50/80/100%)で固定する回帰テスト。
     * 旧既定(0.2, 2.0)より低チャージ時の減衰が顕著(t=0.2で0.232→0.169)かつ、指数を下げたことで
     * カーブがなだらかになる(t=0.8の到達値が0.712→0.730へ上がり、フルチャージ直前の急な跳ね上がりが
     * 緩和される)。出荷値そのものが変わった場合はこのテストで検知される。
     */
    @ParameterizedTest
    @CsvSource({
        "4,  1.0, 0.16853154",   // t=0.2
        "10, 1.0, 0.39688928",   // t=0.5
        "16, 1.0, 0.72977655",   // t=0.8
        "20, 1.0, 1.0",          // t=1.0
    })
    void matchesShippedDefaultCurveAcrossRange(int elapsed, double speed, double expected) {
        assertEquals(expected, MeleeChargeMultiplier.compute(true, elapsed, speed, 0.1, 1.6), 1e-6);
    }

    @Test
    void resultIsAlwaysWithinMinMultiplierAndOne() {
        for (int elapsed = 0; elapsed <= 20; elapsed += 2) {
            double result = MeleeChargeMultiplier.compute(true, elapsed, 1.0, 0.3, 2.0);
            assertTrue(result >= 0.3 - 1e-9 && result <= 1.0 + 1e-9,
                    "result " + result + " out of [0.3, 1.0] for elapsed=" + elapsed);
        }
    }
}
