package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-07-25 attack-speed(絶対値) / attack-speed-bonus(割合) 分離仕様の純粋関数テスト。
 */
class AttackSpeedResolverTest {

    private static final double EPSILON = 1e-9;

    // --- シナリオ1: メインハンド定義済み -> 実効値=著者値そのもの ---
    @Test
    void mainhandDefinedAbsoluteValueBecomesEffectiveSpeed() {
        AttackSpeedResolver.AbsoluteResult result =
                AttackSpeedResolver.resolveAbsolute(6.0, 0.0, AttackSpeedResolver.AUTHORING_FALLBACK);
        assertFalse(result.authoringWarning());
        // base(4.0) + addAmount = 6.0 になるはず
        assertEquals(2.0, result.addNumberAmount(), EPSILON);
    }

    // --- シナリオ2: 未定義 -> TFは干渉しない(ピッケル等、材質既定はitemOwnContributionとして再現) ---
    @Test
    void undefinedMainhandDoesNotInterferePickaxeKeepsMaterialDefault() {
        // ピッケルの材質既定 attack_speed = -2.8 と仮定し、TF他ステが item に explicit component を
        // 作ってしまった場合でも、TFはそれを補填するだけで実効速度をバニラのまま保つ。
        double pickaxeMaterialDefault = -2.8;
        AttackSpeedResolver.AbsoluteResult result =
                AttackSpeedResolver.resolveAbsolute(null, pickaxeMaterialDefault,
                        AttackSpeedResolver.AUTHORING_FALLBACK);
        assertFalse(result.authoringWarning());
        assertEquals(pickaxeMaterialDefault, result.addNumberAmount(), EPSILON);
    }

    @Test
    void undefinedMainhandWithNoExplicitComponentAddsNothing() {
        // バニラが暗黙適用済み(explicit component無し)なら呼び出し側は itemOwnContribution=0 を渡す。
        AttackSpeedResolver.AbsoluteResult result =
                AttackSpeedResolver.resolveAbsolute(null, 0.0, AttackSpeedResolver.AUTHORING_FALLBACK);
        assertEquals(0.0, result.addNumberAmount(), EPSILON);
    }

    // --- シナリオ3: attack-speed-bonusはメインハンド未定義でも常に適用される(素手4.0+10%=4.4) ---
    @Test
    void bonusAppliesRegardlessOfMainhandDefinitionBareHandPlus10Percent() {
        double effectiveBeforeBonus =
                AttackSpeedResolver.effectiveBeforeBonus(null, 0.0, AttackSpeedResolver.AUTHORING_FALLBACK);
        assertEquals(4.0, effectiveBeforeBonus, EPSILON);
        double multiplyAmount = AttackSpeedResolver.resolveBonusMultiplyAmount(
                0.10, effectiveBeforeBonus, AttackSpeedResolver.DEFAULT_LOWER_BOUND);
        double finalSpeed = effectiveBeforeBonus * (1.0 + multiplyAmount);
        assertEquals(4.4, finalSpeed, EPSILON);
    }

    // --- シナリオ4: MULTIPLY_SCALAR_1(真の割合)であって ADD_SCALAR(baseのみに掛かる)ではないこと ---
    @Test
    void bonusIsTrueMultiplyScalar1NotAddScalarOnBaseOnly() {
        // メインハンドで6.0を絶対値指定(=ADD_NUMBERで+2.0)した状態に対し、10%ボーナスを掛ける。
        // ADD_SCALARなら base(4.0)*1.10=4.4 に addAmount(2.0)を足して6.4になってしまうが、
        // MULTIPLY_SCALAR_1は running total(6.0)に掛かるので 6.0*1.10=6.6 が正しい。
        double effectiveBeforeBonus =
                AttackSpeedResolver.effectiveBeforeBonus(6.0, 0.0, AttackSpeedResolver.AUTHORING_FALLBACK);
        assertEquals(6.0, effectiveBeforeBonus, EPSILON);
        double multiplyAmount = AttackSpeedResolver.resolveBonusMultiplyAmount(
                0.10, effectiveBeforeBonus, AttackSpeedResolver.DEFAULT_LOWER_BOUND);
        double finalSpeed = effectiveBeforeBonus * (1.0 + multiplyAmount);
        assertEquals(6.6, finalSpeed, EPSILON);
        assertTrue(Math.abs(finalSpeed - 6.4) > EPSILON, "must not equal the ADD_SCALAR(base-only) result");
    }

    // --- シナリオ7 (2-a): 著者値0以下は警告+4.0へフォールバック ---
    @Test
    void authoringErrorNonPositiveValueFallsBackToFourAndWarns() {
        AttackSpeedResolver.AbsoluteResult zero =
                AttackSpeedResolver.resolveAbsolute(0.0, 0.0, AttackSpeedResolver.AUTHORING_FALLBACK);
        assertTrue(zero.authoringWarning());
        assertEquals(0.0, zero.addNumberAmount(), EPSILON); // 4.0(fallback) - 4.0(base) = 0

        AttackSpeedResolver.AbsoluteResult negative =
                AttackSpeedResolver.resolveAbsolute(-3.0, 0.0, AttackSpeedResolver.AUTHORING_FALLBACK);
        assertTrue(negative.authoringWarning());
        assertEquals(0.0, negative.addNumberAmount(), EPSILON);
    }

    // --- シナリオ7 (2-b): 計算結果が0以下になる場合は4.0へは戻さずlowerBoundへクランプ(exploit防止) ---
    @Test
    void computedResultNonPositiveClampsToLowerBoundNotFour() {
        double effectiveBeforeBonus =
                AttackSpeedResolver.effectiveBeforeBonus(6.0, 0.0, AttackSpeedResolver.AUTHORING_FALLBACK);
        // -95% のデバフが重なった場合、素の計算なら 6.0*0.05=0.3 だが、
        // さらに-99%なら 6.0*0.01=0.06 となり lowerBound(0.1)を割る -> クランプが必要。
        double multiplyAmount = AttackSpeedResolver.resolveBonusMultiplyAmount(
                -0.99, effectiveBeforeBonus, AttackSpeedResolver.DEFAULT_LOWER_BOUND);
        double finalSpeed = effectiveBeforeBonus * (1.0 + multiplyAmount);
        assertEquals(AttackSpeedResolver.DEFAULT_LOWER_BOUND, finalSpeed, EPSILON);
        assertTrue(finalSpeed > 0.0, "must never be zero/negative");
        assertTrue(Math.abs(finalSpeed - 4.0) > EPSILON, "must NOT revert to 4.0 (exploitable)");
    }

    @Test
    void extremeNegativeBonusStillNeverGoesBelowLowerBound() {
        double effectiveBeforeBonus =
                AttackSpeedResolver.effectiveBeforeBonus(4.0, 0.0, AttackSpeedResolver.AUTHORING_FALLBACK);
        double multiplyAmount = AttackSpeedResolver.resolveBonusMultiplyAmount(
                -50.0, effectiveBeforeBonus, AttackSpeedResolver.DEFAULT_LOWER_BOUND);
        double finalSpeed = effectiveBeforeBonus * (1.0 + multiplyAmount);
        assertEquals(AttackSpeedResolver.DEFAULT_LOWER_BOUND, finalSpeed, EPSILON);
    }
}
