package com.trinityforge.combat;

/**
 * One component of an attack fed to {@link SymmetricDamagePipeline}: its type plus the
 * attacker and defender stats for that type. Only components with a non-zero
 * {@code defaultDamage}, {@code flatBonusDamage}, or {@code fixedDamage} are processed
 * (COMBAT_SYSTEM_SPEC 2.1); a component whose only non-zero attacker stat is
 * {@code fixedDamage} still needs to run the pipeline so its step-8 pure-add reaches the
 * defender (T1 2026-07-25: previously such a component was silently skipped, yielding 0 damage).
 */
public record ComponentInput(DamageType type, AttackStats attack, DefenseStats defense,
                             double minComponentDamage) {

    public ComponentInput(DamageType type, AttackStats attack, DefenseStats defense) {
        this(type, attack, defense, Double.NaN);
    }

    public boolean isActive() {
        return attack.defaultDamage() != 0 || attack.flatBonusDamage() != 0 || attack.fixedDamage() != 0;
    }
}
