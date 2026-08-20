package com.trinityforge.mobs;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MobStatScalingTest {

    private static final double DELTA = 1.0e-9;

    @Test
    void scalesMaxHealthWithCoefficient() {
        assertEquals(40.0, MobStatScaling.scaleMaxHealth(20.0, 2.0, 10), DELTA);
        assertEquals(1.0, MobStatScaling.scaleMaxHealth(0.5, 0.0, 5), DELTA);
    }

    @Test
    void scaleMaxHealthGrowthOmittedIsLinearBackCompat() {
        // growth=1.0/interval=1.0 (the 3-arg overload's implicit default) must match the historical
        // base + coeff * level formula exactly — no exponential term applied.
        assertEquals(380.0 + 55.0 * 20, MobStatScaling.scaleMaxHealth(380.0, 55.0, 1.0, 1.0, 20), DELTA);
        assertEquals(MobStatScaling.scaleMaxHealth(380.0, 55.0, 20),
                MobStatScaling.scaleMaxHealth(380.0, 55.0, 1.0, 1.0, 20), DELTA);
    }

    @Test
    void scaleMaxHealthAppliesExponentialGrowth() {
        // (380 + 0*20) * 1.055^20
        double expected = 380.0 * Math.pow(1.055, 20);
        assertEquals(expected, MobStatScaling.scaleMaxHealth(380.0, 0.0, 1.055, 1.0, 20), DELTA);
    }

    @Test
    void scaleMaxHealthHighLevelBreakpointOmittedIsBackCompat() {
        // 5-arg overload (呼び出し側 MobTypeSpawnListener の既存経路) は高レベル加速なしのまま。
        assertEquals(380.0 + 55.0 * 100, MobStatScaling.scaleMaxHealth(380.0, 55.0, 1.0, 1.0, 100), DELTA);
    }

    @Test
    void scaleMaxHealthHighLevelBreakpointAddsAboveThreshold() {
        // base=100, growth=1.0(線形), Lv45から+20/レベル。
        assertEquals(100.0, MobStatScaling.scaleMaxHealth(100.0, 0.0, 1.0, 1.0, 45.0, 20.0, 45), DELTA);
        assertEquals(100.0 + 20.0 * 15, MobStatScaling.scaleMaxHealth(100.0, 0.0, 1.0, 1.0, 45.0, 20.0, 60), DELTA);
        // 閾値未満は完全無干渉。
        assertEquals(100.0, MobStatScaling.scaleMaxHealth(100.0, 0.0, 1.0, 1.0, 45.0, 20.0, 30), DELTA);
    }

    @Test
    void scaleAttackPowerGrowthOmittedIsLinearBackCompat() {
        // AttackCoeffs.ZERO leaves attackPowerGrowth=1.0/interval=1.0 (back-compat default),
        // so attack-power must reduce to the historical base + coeff * level formula.
        MobLevelCoefficients.AttackCoeffs coeffs = new MobLevelCoefficients.AttackCoeffs(
                0.6, 0, 0, 0, 0, 0, 0, 0);
        AttackStats base = AttackStats.plain(6.0);
        AttackStats scaled = MobStatScaling.scaleAttack(base, coeffs, 20);
        assertEquals(6.0 + 0.6 * 20, scaled.defaultDamage(), DELTA);
    }

    @Test
    void scaleAttackPowerAppliesExponentialGrowth() {
        MobLevelCoefficients.AttackCoeffs coeffs = new MobLevelCoefficients.AttackCoeffs(
                0.0, 0, 0, 0, 0, 0, 0, 0, 1.03, 1.0);
        AttackStats base = AttackStats.plain(5.5);
        AttackStats scaled = MobStatScaling.scaleAttack(base, coeffs, 100);
        assertEquals(5.5 * Math.pow(1.03, 100), scaled.defaultDamage(), DELTA);
    }

    @Test
    void scalesDefenseWithoutPrematureClamping() {
        DefenseStats base = new DefenseStats(0.8, 0.1, 0.0, 1.0, 2.0);
        MobLevelCoefficients.DefenseCoeffs coeffs = new MobLevelCoefficients.DefenseCoeffs(0.05, 0.0, 0.0, 0.5);
        DefenseStats scaled = MobStatScaling.scaleDefense(base, coeffs, 2.0, 0.1, 10);
        assertEquals(1.3, scaled.defenseRate(), DELTA);
        assertEquals(0.1, scaled.resistance(), DELTA);
        assertEquals(6.0, scaled.flatDefense(), DELTA);
        assertEquals(3.0, scaled.armorStrength(), DELTA);
    }

    @Test
    void scalesArmorStrengthAsRateWithinRange() {
        DefenseStats base = new DefenseStats(0, 0, 0, 0, 0.2);
        MobLevelCoefficients.DefenseCoeffs coeffs = new MobLevelCoefficients.DefenseCoeffs(0.0, 0.0, 0.0, 0.0);
        // 0.2 + 0.03*10 = 0.5 (範囲内なのでクランプされない)。
        DefenseStats scaled = MobStatScaling.scaleDefense(base, coeffs, 0.2, 0.03, 10);
        assertEquals(0.5, scaled.armorStrength(), DELTA);
    }
}
