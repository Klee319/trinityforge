package com.trinityforge.progression;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure checks for the single-skill use/equip level gate (PROGRESSION 1.6, I3). */
class UseRequirementPolicyTest {

    @Test
    void metWhenPlayerLevelReachesRequirement() {
        assertTrue(UseRequirementPolicy.meets("HEAVY_WEAPONS", 20, Map.of("HEAVY_WEAPONS", 20)));
        assertTrue(UseRequirementPolicy.meets("HEAVY_WEAPONS", 20, Map.of("HEAVY_WEAPONS", 35)));
    }

    @Test
    void notMetWhenBelowRequirementOrSkillUntrained() {
        assertFalse(UseRequirementPolicy.meets("HEAVY_WEAPONS", 20, Map.of("HEAVY_WEAPONS", 19)));
        assertFalse(UseRequirementPolicy.meets("HEAVY_WEAPONS", 20, Map.of("ARCHERY", 99)));
        assertFalse(UseRequirementPolicy.meets("HEAVY_WEAPONS", 20, Map.of()));
    }

    @Test
    void unrestrictedWhenNoSkillOrNonPositiveLevel() {
        assertTrue(UseRequirementPolicy.meets(null, 20, Map.of()));
        assertTrue(UseRequirementPolicy.meets("", 20, Map.of()));
        assertTrue(UseRequirementPolicy.meets("  ", 20, Map.of()));
        assertTrue(UseRequirementPolicy.meets("HEAVY_WEAPONS", 0, Map.of()));
        assertTrue(UseRequirementPolicy.meets("HEAVY_WEAPONS", -5, Map.of()));
    }
}
