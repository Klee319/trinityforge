package com.trinityforge.config.domains;

/**
 * Parameters of the "weapon base attack-power from use-level" formula
 * ({@code combat/damage.yml} {@code weapon-base-formula}):
 * <pre>base attack-power = 1 + (useLevel^exponent / divisor)</pre>
 *
 * <p>A small immutable value object so {@code DerivedItemStats#resolve} stays decoupled from the whole
 * {@link CombatDamageConfig} and the formula step is unit-testable without a live Bukkit config. The
 * formula is only applied to a weapon-category item with a positive use-level and no explicitly-set
 * attack-power (see {@code DerivedItemStats}); {@code enabled == false} disables it entirely.
 *
 * <p>{@code divisor} must be strictly positive (0除算回避). {@link CombatDamageConfig}'s schema enforces
 * {@code > 0} at load time and falls back to the default when violated, so a value produced from config
 * is always valid; the constructor guard is a defensive belt-and-suspenders for direct construction.
 */
public record WeaponBaseFormula(boolean enabled, double exponent, double divisor) {

    public WeaponBaseFormula {
        // Also rejects NaN (NaN > 0.0 is false) so baseAttackPower never yields NaN.
        if (!(divisor > 0.0)) {
            throw new IllegalArgumentException(
                    "weapon-base-formula divisor must be > 0 (0除算回避): " + divisor);
        }
    }

    /** The formula's base attack-power for a positive {@code useLevel}: {@code 1 + useLevel^exponent / divisor}. */
    public double baseAttackPower(int useLevel) {
        return 1.0 + Math.pow(useLevel, exponent) / divisor;
    }

    /** A never-applied formula ({@code enabled == false}) for callers with no combat config to supply. */
    public static WeaponBaseFormula disabled() {
        return new WeaponBaseFormula(false, 2.0, 1000.0);
    }
}
