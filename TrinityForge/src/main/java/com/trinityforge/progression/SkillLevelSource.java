package com.trinityforge.progression;

import java.util.Map;
import java.util.UUID;

/**
 * Supplies a player's skill-tree levels keyed by skill id (ADDON_INTEGRATION_SPEC 5:
 * "ValhallaMMO remains the source of truth for skill levels"). The combat-level mapping
 * ({@link CombatLevelModel}) consumes this, so the Valhalla read path can be swapped for a
 * test fake without touching the mapping math.
 */
@FunctionalInterface
public interface SkillLevelSource {

    /** Current skill levels for the player; an empty map yields the minimum combat level. */
    Map<String, Integer> levelsOf(UUID playerId);

    /** A source with no data, useful as a default before the Valhalla bridge is wired. */
    SkillLevelSource EMPTY = playerId -> Map.of();
}
