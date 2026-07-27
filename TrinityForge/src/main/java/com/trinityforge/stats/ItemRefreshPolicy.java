package com.trinityforge.stats;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure decision of whether an item's lore/attributes need re-assembling against the live tables
 * (SELECTION_SPEC 5: a table edit must reach existing items). Bukkit-free so the refresh policy is
 * unit-testable independent of the listener plumbing that walks a player's inventory/equipment
 * ({@code com.trinityforge.listeners.ItemRefreshListener}).
 *
 * <p>A non-addon item (no rollSeed) never needs refreshing. An addon item needs refreshing when it
 * either predates the {@code data_version}/table-generation stamp (missing stamp) or was stamped
 * with a generation older than the current {@link TableGeneration}; this is a cheap PDC-int
 * comparison so the listener can be called on every hotbar switch / armor change / join without
 * re-deriving stats and re-composing lore for items that are already current.
 */
public final class ItemRefreshPolicy {

    private ItemRefreshPolicy() {
    }

    /**
     * @param hasRollSeed       whether the item's PDC carries a rollSeed (non-addon items never do)
     * @param storedGeneration  the table generation last stamped onto the item, if any
     * @param currentGeneration the live {@link TableGeneration} value
     * @return true when the item should be re-assembled via {@link ItemAssembler#assemble}
     */
    public static boolean needsRefresh(boolean hasRollSeed, Optional<Integer> storedGeneration,
                                       int currentGeneration) {
        Objects.requireNonNull(storedGeneration, "storedGeneration");
        if (!hasRollSeed) {
            return false;
        }
        return storedGeneration.isEmpty() || storedGeneration.get() != currentGeneration;
    }
}
