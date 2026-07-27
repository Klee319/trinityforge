package com.trinityforge.mobs;

import java.util.Objects;

/**
 * Per-stat level scaling coefficients for a mob type (or untagged defaults).
 * Effective value = {@code base + coefficient * effectiveLevel}. Rate fields are clamped to
 * {@code [0, 1]} after scaling.
 *
 * <p>{@code maxHealth}/{@code maxHealthGrowth}/{@code maxHealthGrowthInterval} together form the
 * per-level part of a {@link ConversionPolicy.Ramp} for max-health (see
 * {@code MobStatScaling#scaleMaxHealth}), and {@code AttackCoeffs#attackPower}/{@code
 * attackPowerGrowth}/{@code attackPowerGrowthInterval} do the same for the attacker-side
 * attack-power (see {@code MobStatScaling#scaleAttack}): {@code effective = (base + coeff * level)
 * * growth^(level / growthInterval)}. {@code growth == 1.0} (the default) makes the geometric term
 * vanish, so every pre-existing config keeps its exact linear behaviour. Growth is intentionally
 * scoped to max-health and attack-power only for now; other stats stay purely linear, but follow
 * the same {@code <field>}/{@code <field>Growth}/{@code <field>GrowthInterval} shape so a future
 * stat can opt in the same way.
 */
public record MobLevelCoefficients(
        double maxHealth,
        double armorStrength,
        DefenseCoeffs physical,
        DefenseCoeffs magical,
        AttackCoeffs attack,
        double maxHealthGrowth,
        double maxHealthGrowthInterval) {

    public static final MobLevelCoefficients ZERO = new MobLevelCoefficients(
            0.0, 0.0, DefenseCoeffs.ZERO, DefenseCoeffs.ZERO, AttackCoeffs.ZERO);

    public MobLevelCoefficients {
        Objects.requireNonNull(physical, "physical");
        Objects.requireNonNull(magical, "magical");
        Objects.requireNonNull(attack, "attack");
        maxHealth = finiteOrZero(maxHealth);
        armorStrength = finiteOrZero(armorStrength);
        if (!Double.isFinite(maxHealthGrowth) || maxHealthGrowth < 0.0) {
            maxHealthGrowth = 1.0;
        }
        if (!(maxHealthGrowthInterval > 0.0) || !Double.isFinite(maxHealthGrowthInterval)) {
            maxHealthGrowthInterval = 1.0;
        }
    }

    /** Back-compat: attack coeffs default to zero; max-health growth defaults to 1.0 (linear). */
    public MobLevelCoefficients(double maxHealth, double armorStrength,
                                DefenseCoeffs physical, DefenseCoeffs magical) {
        this(maxHealth, armorStrength, physical, magical, AttackCoeffs.ZERO, 1.0, 1.0);
    }

    /** Back-compat: max-health growth defaults to 1.0 (linear, pre-growth behaviour). */
    public MobLevelCoefficients(double maxHealth, double armorStrength,
                                DefenseCoeffs physical, DefenseCoeffs magical,
                                AttackCoeffs attack) {
        this(maxHealth, armorStrength, physical, magical, attack, 1.0, 1.0);
    }

    public record DefenseCoeffs(
            double defenseRate,
            double resistance,
            double damageReduction,
            double flatDefense) {

        public static final DefenseCoeffs ZERO = new DefenseCoeffs(0.0, 0.0, 0.0, 0.0);

        public DefenseCoeffs {
            defenseRate = finiteOrZero(defenseRate);
            resistance = finiteOrZero(resistance);
            damageReduction = finiteOrZero(damageReduction);
            flatDefense = finiteOrZero(flatDefense);
        }
    }

    public record AttackCoeffs(
            double attackPower,
            double flatBonusDamage,
            double percentBonusDamage,
            double penetration,
            double critChance,
            double critDamage,
            double damageModifier,
            double fixedDamage,
            double attackPowerGrowth,
            double attackPowerGrowthInterval) {

        public static final AttackCoeffs ZERO = new AttackCoeffs(0, 0, 0, 0, 0, 0, 0, 0, 1.0, 1.0);

        public AttackCoeffs {
            attackPower = finiteOrZero(attackPower);
            flatBonusDamage = finiteOrZero(flatBonusDamage);
            percentBonusDamage = finiteOrZero(percentBonusDamage);
            penetration = finiteOrZero(penetration);
            critChance = finiteOrZero(critChance);
            critDamage = finiteOrZero(critDamage);
            damageModifier = finiteOrZero(damageModifier);
            fixedDamage = finiteOrZero(fixedDamage);
            if (!Double.isFinite(attackPowerGrowth) || attackPowerGrowth < 0.0) {
                attackPowerGrowth = 1.0;
            }
            if (!(attackPowerGrowthInterval > 0.0) || !Double.isFinite(attackPowerGrowthInterval)) {
                attackPowerGrowthInterval = 1.0;
            }
        }

        /** Back-compat: attack-power growth defaults to 1.0 (linear, pre-growth behaviour). */
        public AttackCoeffs(double attackPower, double flatBonusDamage, double percentBonusDamage,
                            double penetration, double critChance, double critDamage,
                            double damageModifier, double fixedDamage) {
            this(attackPower, flatBonusDamage, percentBonusDamage, penetration, critChance,
                    critDamage, damageModifier, fixedDamage, 1.0, 1.0);
        }
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
