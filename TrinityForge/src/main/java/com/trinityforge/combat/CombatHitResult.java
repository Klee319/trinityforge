package com.trinityforge.combat;

/**
 * Outcome of one TF symmetric-pipeline hit: final damage and whether any component crit.
 */
public record CombatHitResult(double damage, boolean crit) {

    public static CombatHitResult of(double damage, boolean crit) {
        return new CombatHitResult(damage, crit);
    }

    public static CombatHitResult noHit() {
        return new CombatHitResult(0.0, false);
    }
}
