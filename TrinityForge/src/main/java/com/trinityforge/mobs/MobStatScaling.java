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
        int level = Math.max(0, effectiveLevel);
        ConversionPolicy.Ramp ramp = new ConversionPolicy.Ramp(baseMaxHealth, maxHealthCoeff, growth, growthInterval);
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
                base.fixedDamage() + coeffs.fixedDamage() * level);
    }

}
