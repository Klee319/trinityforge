package com.trinityforge.stats;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Optional machine-readable "when does this stat fire" declaration for one {@code stats/lore.yml}
 * entry ({@code trigger:} block). Absent for a stat means "not yet declared" (see the ratchet
 * allow-list in {@code LoreConfigDeclarationTest}), not "no trigger".
 */
public record StatTrigger(StatTriggerWhen when, StatSourceScope sources, Set<StatAppliesTo> appliesTo) {

    public StatTrigger {
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(appliesTo, "appliesTo");
        if (appliesTo.isEmpty()) {
            throw new IllegalArgumentException("trigger.applies-to must not be empty");
        }
        appliesTo = Set.copyOf(EnumSet.copyOf(appliesTo));
    }
}
