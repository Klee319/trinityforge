package com.trinityforge.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 序盤モブ火力の緩和倍率(2026-07-28、{@code combat/damage.yml} の {@code early-level-attack})。
 * 出荷既定は {@code until-level: 10} / {@code level-0-multiplier: 0.7}。
 */
class EarlyLevelAttackSofteningTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("Lv0は level-0-multiplier そのもの、until-level で等倍に戻る")
    void rampsFromLevel0MultiplierToOne() {
        assertEquals(0.7, EarlyLevelAttackSoftening.multiplier(true, 10, 0.7, 0), EPS);
        assertEquals(0.85, EarlyLevelAttackSoftening.multiplier(true, 10, 0.7, 5), EPS);
        assertEquals(1.0, EarlyLevelAttackSoftening.multiplier(true, 10, 0.7, 10), EPS);
    }

    @Test
    @DisplayName("until-level を超えたレベルは中盤以降の校正を一切動かさない(常に等倍)")
    void aboveUntilLevelIsUnchanged() {
        assertEquals(1.0, EarlyLevelAttackSoftening.multiplier(true, 10, 0.7, 11), EPS);
        assertEquals(1.0, EarlyLevelAttackSoftening.multiplier(true, 10, 0.7, 100), EPS);
    }

    @Test
    @DisplayName("enabled=false / until-level<=0 / 倍率>=1 のいずれでも緩和は無効")
    void disabledFormsYieldNeutralMultiplier() {
        assertEquals(1.0, EarlyLevelAttackSoftening.multiplier(false, 10, 0.7, 0), EPS);
        assertEquals(1.0, EarlyLevelAttackSoftening.multiplier(true, 0, 0.7, 0), EPS);
        assertEquals(1.0, EarlyLevelAttackSoftening.multiplier(true, -5, 0.7, 0), EPS);
        assertEquals(1.0, EarlyLevelAttackSoftening.multiplier(true, 10, 1.0, 0), EPS);
        assertEquals(1.0, EarlyLevelAttackSoftening.multiplier(true, 10, Double.NaN, 0), EPS);
    }

    @Test
    @DisplayName("負のレベル/負の倍率は安全側へ丸める(ダメージが負や増幅にならない)")
    void degenerateInputsAreClamped() {
        assertEquals(0.7, EarlyLevelAttackSoftening.multiplier(true, 10, 0.7, -3), EPS);
        assertEquals(0.0, EarlyLevelAttackSoftening.multiplier(true, 10, -1.0, 0), EPS);
    }
}
