package com.trinityforge.combat;

/**
 * Damage component type. The attack type is fixed by its source (COMBAT_SYSTEM_SPEC 2.2):
 * weapons/tools -> PHYSICAL, catalysts/spells -> MAGICAL.
 *
 * <p>{@code TYPELESS} is the fallback for attacks that classify as neither physical nor magical. It
 * bypasses both {@link DefenseStats} sides (no defenseRate/resistance/reduction applied) rather
 * than cancelling or deferring to vanilla; whole-attack dodge still applies.
 */
public enum DamageType {
    PHYSICAL,
    MAGICAL,
    TYPELESS
}
