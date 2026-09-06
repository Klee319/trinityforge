package com.trinityforge.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ワールド倍率を技の {@code damage-percent} へ掛ける純関数。
 * {@code MobAbilityExecutor#applyHit} が同じ関数を通すので、ここがずれたら実ダメージもずれる。
 */
class MobAbilityDamageScaleTest {

    @Test
    @DisplayName("技倍率は damage-percent に世界倍率を掛ける。非正・非有限は 1.0 扱い")
    void scaledAbilityPercentMultipliesAndSanitizes() {
        assertEquals(1.085, MobAbilityExecutor.scaledAbilityPercent(1.55, 0.70), 1e-9);
        assertEquals(1.55, MobAbilityExecutor.scaledAbilityPercent(1.55, 1.0), 1e-9);
        assertEquals(1.55, MobAbilityExecutor.scaledAbilityPercent(1.55, 0.0), 1e-9);
        assertEquals(1.55, MobAbilityExecutor.scaledAbilityPercent(1.55, -1.0), 1e-9);
        assertEquals(1.55, MobAbilityExecutor.scaledAbilityPercent(1.55, Double.NaN), 1e-9);
    }
}
