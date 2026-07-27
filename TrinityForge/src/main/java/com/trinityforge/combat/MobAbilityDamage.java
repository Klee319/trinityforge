package com.trinityforge.combat;

/**
 * Main-thread transient marker for addon-driven mob ABILITY damage (B1: EliteMobs script/power
 * {@code DAMAGE} actions). Such damage reaches the player through a synthetic
 * {@code target.damage(amount, mob)} call whose Bukkit cause is indistinguishable from a real melee
 * hit ({@code ENTITY_ATTACK}), so without this marker {@code CombatListener}'s mob→player melee
 * path would re-route the ability through the PHYSICAL pipeline (and, on an attack-stamped mob,
 * overwrite the addon's magical routing with the stamped melee number).
 *
 * <p>Contract: the addon marks immediately before its synchronous damage call and clears in a
 * {@code finally}; TrinityForge's own damage listeners treat an active mark as "the addon owns this
 * hit" and stand down. Everything runs synchronously on the server main thread (Bukkit damage
 * events are synchronous), so no thread-locals are needed. The mark is a DEPTH COUNTER, not a
 * boolean: an ability's damage call can synchronously trigger another marked ability damage (e.g.
 * an EliteMobs boss script bound to its own damage event running a nested {@code DAMAGE} action),
 * and the inner {@code finally} must not strip the outer call's mark before the outer hit has been
 * classified by the listeners.
 */
public final class MobAbilityDamage {

    private static int depth = 0;

    private MobAbilityDamage() {
    }

    /** Marks the immediately following synchronous damage call as addon ability damage. */
    public static void mark() {
        depth++;
    }

    /** Clears one mark; the marking addon must call this in a {@code finally}. */
    public static void clear() {
        if (depth > 0) depth--;
    }

    /** True while inside a marked ability-damage call. Read-only (the marker's finally clears). */
    public static boolean isActive() {
        return depth > 0;
    }
}
