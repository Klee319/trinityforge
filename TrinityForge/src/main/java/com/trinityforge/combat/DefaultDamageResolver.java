package com.trinityforge.combat;

/**
 * Derives the default damage of an attack: the source's base damage scaled only by the mob/combat
 * level and a global per-type coefficient. This is the sole gear-independent source of raw damage
 * growth (DESIGN v0.4, COMBAT_SYSTEM_SPEC 2.2). All knobs are config-driven (combat/damage.yml).
 *
 * <p>Physical and magical are symmetric: physical scales the vanilla attack damage, magical scales
 * the catalyst/spell base damage (the latter arrives from Ars in M2). Both share the same level
 * curve so the two components grow in lockstep.
 */
public final class DefaultDamageResolver {

    private final double physicalCoefficient;
    private final double magicalCoefficient;
    private final double perLevel;

    public DefaultDamageResolver(double physicalCoefficient, double magicalCoefficient, double perLevel) {
        requireFinite(physicalCoefficient, "physicalCoefficient");
        requireFinite(magicalCoefficient, "magicalCoefficient");
        requireFinite(perLevel, "perLevel");
        this.physicalCoefficient = physicalCoefficient;
        this.magicalCoefficient = magicalCoefficient;
        this.perLevel = perLevel;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }

    /**
     * @param vanillaBaseDamage raw vanilla attack damage of the source
     * @param level             EliteMobs combat / mob level; negative levels are clamped to 0
     */
    public double physicalDefaultDamage(double vanillaBaseDamage, int level) {
        return scaled(vanillaBaseDamage, level, physicalCoefficient);
    }

    /**
     * Magical counterpart of {@link #physicalDefaultDamage}: the catalyst/spell base damage scaled by
     * the same gear-independent level curve and the magical coefficient (COMBAT_SYSTEM_SPEC 2.2).
     *
     * @param spellBaseDamage base damage of the spell/catalyst (from Ars, M2)
     * @param level           EliteMobs combat / mob level; negative levels are clamped to 0
     */
    public double magicalDefaultDamage(double spellBaseDamage, int level) {
        return scaled(spellBaseDamage, level, magicalCoefficient);
    }

    private double scaled(double base, int level, double coefficient) {
        int safeLevel = Math.max(0, level);
        return base * (1 + perLevel * safeLevel) * coefficient;
    }
}
