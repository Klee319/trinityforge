package com.trinityforge.combat;

import java.util.List;

/**
 * Splits an attack into physical / magical components, runs each independently through the
 * 8-step calculator, and sums them (COMBAT_SYSTEM_SPEC 2.1). The crit roll is resolved per
 * component via the injected {@link CritResolver}; the dodge roll is resolved once for the whole
 * attack via the injected {@link DodgeResolver}.
 */
public final class SymmetricDamagePipeline {

    private final CritResolver critResolver;
    private final DodgeResolver dodgeResolver;
    private final double minComponentDamage;

    public SymmetricDamagePipeline(CritResolver critResolver, DodgeResolver dodgeResolver,
                                   double minComponentDamage) {
        this.critResolver = critResolver;
        this.dodgeResolver = dodgeResolver;
        this.minComponentDamage = minComponentDamage;
    }

    /**
     * Total damage across all active components, or {@code 0} when the defender dodges. Dodge is
     * rolled once for the whole attack ahead of the per-component 8-step math (回避 = 攻撃全体を
     * 無効化, magical components included, Q3), so the "no damage on dodge" outcome is decided
     * inside TrinityForge rather than by a separate mitigation layer (LD-9).
     *
     * @param dodgeChance the defender's whole-attack dodge chance [0,1]; a non-positive value never dodges
     */
    public double compute(List<ComponentInput> components, double dodgeChance) {
        return computeResult(components, dodgeChance).damage();
    }

    /**
     * Same as {@link #compute} but also reports whether any active component rolled a crit
     * (for VFX such as {@link CritFlash}).
     */
    public CombatHitResult computeResult(List<ComponentInput> components, double dodgeChance) {
        if (dodgeResolver.rolls(dodgeChance)) {
            return CombatHitResult.noHit();
        }
        double total = 0;
        boolean anyCrit = false;
        for (ComponentInput component : components) {
            if (!component.isActive()) {
                continue;
            }
            boolean crit = critResolver.rolls(component.attack().critChance());
            anyCrit |= crit;
            total += ComponentDamageCalculator.compute(
                    component.attack(), component.defense(), crit,
                    Double.isFinite(component.minComponentDamage())
                            ? component.minComponentDamage() : minComponentDamage);
        }
        return CombatHitResult.of(total, anyCrit);
    }
}
