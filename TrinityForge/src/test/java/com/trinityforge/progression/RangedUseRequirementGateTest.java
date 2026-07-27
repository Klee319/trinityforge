package com.trinityforge.progression;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documents the decision the ranged use-level gate applies (bow/crossbow at {@code EntityShootBowEvent},
 * trident at {@code ProjectileLaunchEvent} in {@code CombatListener}): the shot is blocked exactly when
 * the shooter fails the used weapon's single-skill requirement, i.e. {@code !UseRequirementPolicy.meets}.
 * The gate reuses this same predicate as the melee gate, so ranged and melee share one rule.
 *
 * <p>The listener's Bukkit event glue (reading {@code EntityShootBowEvent.getBow()} /
 * {@code Trident.getItem()}, cancelling, sending the action bar) needs a live server and is verified by
 * the implementation; only the pure block/pass branch is unit-tested here.
 */
class RangedUseRequirementGateTest {

    /** Mirrors {@code CombatListener.rangedWeaponUseBlocked}'s decision without the Bukkit item read. */
    private static boolean blocked(String skill, int required, Map<String, Integer> levels) {
        return !UseRequirementPolicy.meets(skill, required, levels);
    }

    @Test
    void unrestrictedRangedWeaponPassesThrough() {
        assertFalse(blocked(null, 0, Map.of()));
        assertFalse(blocked("", 30, Map.of()));
        assertFalse(blocked("ARCHERY", 0, Map.of()));
    }

    @Test
    void metRequirementPassesThrough() {
        assertFalse(blocked("ARCHERY", 25, Map.of("ARCHERY", 25)));
        assertFalse(blocked("ARCHERY", 25, Map.of("ARCHERY", 40)));
    }

    @Test
    void unmetRequirementIsBlocked() {
        assertTrue(blocked("ARCHERY", 25, Map.of("ARCHERY", 24)));
        assertTrue(blocked("ARCHERY", 25, Map.of()));
        assertTrue(blocked("ARCHERY", 25, Map.of("HEAVY_WEAPONS", 99)));
    }
}
