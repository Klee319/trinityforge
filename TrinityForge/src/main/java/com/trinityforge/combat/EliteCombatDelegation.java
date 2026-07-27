package com.trinityforge.combat;

/**
 * Main-thread transient marker for CMB-02 (2026-07-25, 課題3): the EliteMobs fork's {@code
 * TrinityForgeCombatListener} already routed a player→elite melee/projectile hit through TrinityForge's
 * OWN symmetric pipeline (defense/dodge/crit/penetration + combat-level scaling) via the synchronous
 * {@code EliteMobDamagedByPlayerEvent} it fires from inside its {@code NORMAL}-priority raw-event
 * handler — which runs, start to finish, BEFORE TrinityForge's own {@code CombatListener} (registered at
 * {@code HIGH} on the same raw {@code EntityDamageByEntityEvent}) gets a turn. Without this marker,
 * {@code CombatListener} re-derives the elite's defense/dodge and re-rolls crit/penetration a SECOND time
 * against the fork's already-mitigated number (and re-applies the combat-level scale + physical.base
 * coefficient an extra time on top), silently making every elite roughly twice as tanky as designed
 * (CMB-02 — confirmed by two independent audits; see the elite→player direction's existing analogous
 * guard, {@code hasTrinityForgeAttackStamp}, in {@code TrinityForgeCombatListener}).
 *
 * <p>Contract (mirrors {@link MobAbilityDamage}, the established precedent for this exact class of
 * "an addon already owns this hit" signal): the fork marks immediately before firing/processing the
 * delegated hit and clears in a {@code finally}, spanning the ENTIRE window from its own {@code NORMAL}
 * handler through TrinityForge's {@code HIGH} handler for the SAME raw event (both run synchronously,
 * within the same {@code callEvent} dispatch, so a plain depth counter — no real {@code
 * java.util.concurrent.ThreadLocal} — is sufficient and matches this codebase's existing convention;
 * everything here is single-threaded on the Bukkit main thread). TrinityForge's {@code CombatListener}
 * treats an active mark as "the fork already fully priced this hit" and skips ONLY its own
 * defense/dodge/crit/penetration/combat-level recomputation for that one hit — it still runs bleed,
 * AoE splash, and combat-skill-EXP off the fork-supplied final damage, since those attacker-side systems
 * are gear/weapon-source, not victim-defense, and would otherwise silently stop working against elites
 * (a regression the CMB-02 fix does not intend).
 */
public final class EliteCombatDelegation {

    private static int depth = 0;

    private EliteCombatDelegation() {
    }

    /** Marks the following synchronous player↔elite hit as already fully priced by the fork. */
    public static void mark() {
        depth++;
    }

    /** Clears one mark; the marking fork listener must call this in a {@code finally}. */
    public static void clear() {
        if (depth > 0) depth--;
    }

    /** True while inside a marked fork-delegated elite hit. Read-only (the marker's finally clears). */
    public static boolean isActive() {
        return depth > 0;
    }
}
