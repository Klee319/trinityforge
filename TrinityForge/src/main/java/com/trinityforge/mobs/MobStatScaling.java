package com.trinityforge.mobs;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;

/**
 * Applies level coefficients to base mob stats:
 * {@code effective = base + coefficient * effectiveLevel}.
 * Values are not domain-clamped here; the shared combat choke applies configured bounds after scaling.
 */
public final class MobStatScaling {

    private MobStatScaling() {
    }

    public static DefenseStats scaleDefense(DefenseStats base,
                                            MobLevelCoefficients.DefenseCoeffs coeffs,
                                            double armorStrengthBase,
                                            double armorStrengthCoeff,
                                            int effectiveLevel) {
        int level = Math.max(0, effectiveLevel);
        double armor = armorStrengthBase + armorStrengthCoeff * level;
        return new DefenseStats(
                base.defenseRate() + coeffs.defenseRate() * level,
                base.resistance() + coeffs.resistance() * level,
                base.damageReduction() + coeffs.damageReduction() * level,
                base.flatDefense() + coeffs.flatDefense() * level,
                armor);
    }

    /** Back-compat: purely linear (no growth). Delegates to the growth-aware overload below. */
    public static double scaleMaxHealth(double baseMaxHealth, double maxHealthCoeff, int effectiveLevel) {
        return scaleMaxHealth(baseMaxHealth, maxHealthCoeff, 1.0, 1.0, effectiveLevel);
    }

    /**
     * {@code effective = (base + coeff * level) * growth^(level / growthInterval)}, reusing
     * {@link ConversionPolicy.Ramp} (the same exponential ramp {@code mob-import.yml} uses) so
     * mob-types tracks the same growth curve without a second implementation.
     * {@code growth == 1.0} reduces this to the historical linear {@code base + coeff * level}.
     */
    public static double scaleMaxHealth(double baseMaxHealth, double maxHealthCoeff,
                                        double growth, double growthInterval, int effectiveLevel) {
        return scaleMaxHealth(baseMaxHealth, maxHealthCoeff, growth, growthInterval,
                Double.POSITIVE_INFINITY, 0.0, effectiveLevel);
    }

    /**
     * Same as the 5-arg overload but also applies {@link ConversionPolicy.Ramp}'s high-level
     * breakpoint (2026-08-03, 45+難易度修正): {@code + highLevelPerLevel * (level - highLevelFrom)}
     * for {@code level >= highLevelFrom}. {@code highLevelFrom = Double.POSITIVE_INFINITY} (the 5-arg
     * overload's implicit default) never triggers, so existing callers are unaffected.
     */
    public static double scaleMaxHealth(double baseMaxHealth, double maxHealthCoeff,
                                        double growth, double growthInterval,
                                        double highLevelFrom, double highLevelPerLevel,
                                        int effectiveLevel) {
        int level = Math.max(0, effectiveLevel);
        ConversionPolicy.Ramp ramp = new ConversionPolicy.Ramp(baseMaxHealth, maxHealthCoeff, growth,
                growthInterval, highLevelFrom, highLevelPerLevel);
        return Math.max(1.0, ramp.at(level));
    }

    /**
     * attack-power reuses {@link ConversionPolicy.Ramp} the same way {@link #scaleMaxHealth} does
     * ({@code effective = (base + coeff * level) * growth^(level / growthInterval)}); every other
     * attack field stays purely linear ({@code base + coeff * level}).
     * {@code coeffs.attackPowerGrowth() == 1.0} reduces attack-power to the historical linear form.
     */
    public static AttackStats scaleAttack(AttackStats base, MobLevelCoefficients.AttackCoeffs coeffs,
                                          int effectiveLevel) {
        int level = Math.max(0, effectiveLevel);
        ConversionPolicy.Ramp attackPowerRamp = new ConversionPolicy.Ramp(
                base.defaultDamage(), coeffs.attackPower(),
                coeffs.attackPowerGrowth(), coeffs.attackPowerGrowthInterval());
        return new AttackStats(
                attackPowerRamp.at(level),
                base.flatBonusDamage() + coeffs.flatBonusDamage() * level,
                base.percentBonusDamage() + coeffs.percentBonusDamage() * level,
                base.critChance() + coeffs.critChance() * level,
                base.critDamage() + coeffs.critDamage() * level,
                base.penetration() + coeffs.penetration() * level,
                base.damageModifier() + coeffs.damageModifier() * level,
                base.fixedDamage() + coeffs.fixedDamage() * level,
                // magic-ratio はレベルで伸びる「量」ではなく攻撃の「型」の分類なので、
                // 他の係数と違いレベル係数を持たず base の値をそのまま通す(2026-08-02)。
                base.magicRatio());
    }

}
