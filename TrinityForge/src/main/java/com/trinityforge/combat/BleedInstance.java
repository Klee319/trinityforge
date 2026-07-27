package com.trinityforge.combat;

import java.util.Objects;
import java.util.UUID;

/**
 * One active bleed on a victim (SKILL_TREE_SPEC 6.2, Q3 = (c) TF-routed DoT). Immutable; the
 * {@code BleedService} advances it one application at a time. Each application deals
 * {@code damagePerTick} via {@code SymmetricCombatService.bleedFinalDamageFlat}, which applies ONLY
 * 被ダメージ軽減(damageReduction) — every other defender stat (耐性/防御率/守備力/防具強度/回避/
 * 固定ダメージ/バニラ防具) is intentionally bypassed — and writes the result straight to health so
 * vanilla armor cannot re-reduce it (TrinityForge stays the sole damage authority, LD-9), for
 * {@code remainingTicks} more applications.
 *
 * @param attackerId    the original attacker, credited as the source of each bleed tick
 * @param damagePerTick base physical damage each application deals before damageReduction (>= 0, finite)
 * @param remainingTicks number of applications left (clamped to >= 0)
 */
public record BleedInstance(UUID attackerId, double damagePerTick, int remainingTicks) {

    public BleedInstance {
        Objects.requireNonNull(attackerId, "attackerId");
        if (!Double.isFinite(damagePerTick) || damagePerTick < 0) {
            throw new IllegalArgumentException("damagePerTick must be finite and >= 0: " + damagePerTick);
        }
        if (remainingTicks < 0) {
            remainingTicks = 0;
        }
    }

    public boolean isExpired() {
        return remainingTicks <= 0;
    }

    /** This bleed after one application elapses (one fewer remaining). */
    public BleedInstance afterTick() {
        return new BleedInstance(attackerId, damagePerTick, remainingTicks - 1);
    }
}
