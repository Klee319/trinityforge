package com.trinityforge.listeners;

import com.trinityforge.pdc.PlayerData;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared held-perks read for the 4 drop-table listeners (mining/woodcutting/digging/fishing): same
 * fail-safe pattern as {@code DedicatedEffectsConfig}'s private {@code heldPerksOf} (a {@code null}
 * player, an unreadable PDC, or any internal exception yields an empty set rather than throwing out of a
 * block-break/fish-catch handler). Kept as a single seam so the four listeners agree on one read
 * instead of duplicating the try/catch four times.
 */
final class DropTableGateSupport {

    private DropTableGateSupport() {
    }

    static Set<String> heldPerksOf(Player player) {
        if (player == null) {
            return Set.of();
        }
        try {
            List<String> perks = PlayerData.of(player).heldPerks();
            return perks == null ? Set.of() : new LinkedHashSet<>(perks);
        } catch (RuntimeException ex) {
            return Set.of();
        }
    }
}
