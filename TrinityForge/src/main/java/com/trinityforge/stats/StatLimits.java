package com.trinityforge.stats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional machine-readable bound declaration for one {@code stats/lore.yml} entry ({@code limits:}
 * block). Every field is optional (null = not declared). Each bound is a {@link StatBound}: a literal
 * value plus an optional {@code -ref} pointer resolved by {@link CapRefResolver} and asserted equal to
 * the literal by the constraint test.
 *
 * @param cap              declared upper bound of the stat's own value (nullable)
 * @param floor            declared lower bound of the stat's own value (nullable)
 * @param minPieces        armor-piece-count threshold this stat's value depends on (nullable)
 * @param maxDistance      block-distance bound relevant to this stat's mechanic (nullable)
 * @param maxDurationTicks tick-duration bound relevant to this stat's mechanic (nullable)
 * @param stacking         how multiple sources of this stat combine (nullable = undeclared)
 */
public record StatLimits(StatBound cap, StatBound floor, StatBound minPieces,
                         StatBound maxDistance, StatBound maxDurationTicks,
                         StatStacking stacking) {

    public static final StatLimits EMPTY = new StatLimits(null, null, null, null, null, null);

    /**
     * The declared bounds, keyed by their {@code stats/lore.yml} field name (e.g. {@code "cap"},
     * {@code "max-distance"}, {@code "max-duration-ticks"}). Undeclared bounds are omitted, not
     * present with a null value. Constraint tests iterate this map instead of hand-listing each
     * bound field, so adding a new bound kind here does not require a new test method.
     */
    public Map<String, StatBound> declaredBounds() {
        Map<String, StatBound> bounds = new LinkedHashMap<>();
        if (cap != null) {
            bounds.put("cap", cap);
        }
        if (floor != null) {
            bounds.put("floor", floor);
        }
        if (minPieces != null) {
            bounds.put("min-pieces", minPieces);
        }
        if (maxDistance != null) {
            bounds.put("max-distance", maxDistance);
        }
        if (maxDurationTicks != null) {
            bounds.put("max-duration-ticks", maxDurationTicks);
        }
        return Map.copyOf(bounds);
    }
}
