package com.trinityforge.mobs;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;

/**
 * One "強さ" (strength) override layer for {@code combat/mob-overrides.yml} (2026-07-26 ダンジョン×
 * モブ単位オーバーライド新設): every field is nullable/boxed and means "leave the underlying
 * {@link MobProfile} value untouched" when absent, so an operator can override e.g. only
 * {@code max-health} without having to restate the whole profile. {@link #applyTo(MobProfile)} performs
 * this field-by-field merge; stacking two overrides (default layer then world-specific layer) is simply
 * two sequential {@link #applyTo} calls, each only touching the fields it carries.
 *
 * <p>{@link #physical()}/{@link #magical()}/{@link #attack()} are themselves partial (nested nullable
 * fields), mirroring the same key vocabulary {@code combat/mob-profiles.yml} uses
 * ({@link DefenseStats}/{@link AttackStats}), so an operator only writes the sub-fields they actually
 * want to change.
 */
public record MobStatOverride(
        Integer level,
        Double maxHealth,
        Double armorStrength,
        DefenseFieldOverride physical,
        DefenseFieldOverride magical,
        AttackFieldOverride attack) {

    /** No fields set — {@link #applyTo(MobProfile)} is then a no-op copy. */
    public static final MobStatOverride EMPTY = new MobStatOverride(null, null, null, null, null, null);

    public boolean isEmpty() {
        return level == null && maxHealth == null && armorStrength == null
                && physical == null && magical == null && attack == null;
    }

    /**
     * Returns a copy of {@code base} with every non-null field of this override applied. Fields left
     * {@code null} here keep {@code base}'s value untouched (項目単位マージ、2026-07-26 mob-overrides
     * §2). {@link #armorStrength()} — if set — is applied identically to both {@link #physical()} and
     * {@link #magical()} components (mirrors {@link MobProfile}'s invariant that both components share
     * one armor-strength value).
     */
    public MobProfile applyTo(MobProfile base) {
        if (base == null || isEmpty()) {
            return base;
        }
        int newLevel = level != null ? level : base.level();
        double newMaxHealth = maxHealth != null ? maxHealth : base.maxHealth();
        DefenseStats newPhysical = mergeDefense(base.physical(), physical, armorStrength);
        DefenseStats newMagical = mergeDefense(base.magical(), magical, armorStrength);
        AttackStats newAttack = mergeAttack(base.attack(), attack);
        return new MobProfile(base.id(), newLevel, base.dungeonTheme(), newPhysical, newMagical,
                newAttack, newMaxHealth, base.dynamic());
    }

    private static DefenseStats mergeDefense(DefenseStats base, DefenseFieldOverride override,
                                              Double armorStrengthOverride) {
        if (override == null && armorStrengthOverride == null) {
            return base;
        }
        double defenseRate = override != null && override.defenseRate() != null
                ? override.defenseRate() : base.defenseRate();
        double resistance = override != null && override.resistance() != null
                ? override.resistance() : base.resistance();
        double damageReduction = override != null && override.damageReduction() != null
                ? override.damageReduction() : base.damageReduction();
        double flatDefense = override != null && override.flatDefense() != null
                ? override.flatDefense() : base.flatDefense();
        double armorStrength = armorStrengthOverride != null ? armorStrengthOverride : base.armorStrength();
        return new DefenseStats(defenseRate, resistance, damageReduction, flatDefense, armorStrength);
    }

    private static AttackStats mergeAttack(AttackStats base, AttackFieldOverride override) {
        if (override == null) {
            return base;
        }
        return new AttackStats(
                override.defaultDamage() != null ? override.defaultDamage() : base.defaultDamage(),
                override.flatBonusDamage() != null ? override.flatBonusDamage() : base.flatBonusDamage(),
                override.percentBonusDamage() != null ? override.percentBonusDamage() : base.percentBonusDamage(),
                override.critChance() != null ? override.critChance() : base.critChance(),
                override.critDamage() != null ? override.critDamage() : base.critDamage(),
                override.penetration() != null ? override.penetration() : base.penetration(),
                override.damageModifier() != null ? override.damageModifier() : base.damageModifier(),
                override.fixedDamage() != null ? override.fixedDamage() : base.fixedDamage());
    }

    /** Partial {@link DefenseStats} override; {@code armorStrength} lives on the parent record instead
     *  (shared across physical/magical, matching {@link MobProfile#armorStrength()}). */
    public record DefenseFieldOverride(Double defenseRate, Double resistance, Double damageReduction,
                                        Double flatDefense) {
    }

    /** Partial {@link AttackStats} override. */
    public record AttackFieldOverride(Double defaultDamage, Double flatBonusDamage, Double percentBonusDamage,
                                       Double critChance, Double critDamage, Double penetration,
                                       Double damageModifier, Double fixedDamage) {
    }
}
