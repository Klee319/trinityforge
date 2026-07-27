package com.trinityforge.progression;

import com.trinityforge.pdc.BindType;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure predicate: may {@code actor} use an item with the given bind type and owner?
 *
 * <ul>
 *   <li>No bind / {@link BindType#TRADEABLE} / no owner → usable by anyone.</li>
 *   <li>{@link BindType#SOULBOUND} or {@link BindType#OWNER_BOUND} with an owner → only that owner.</li>
 *   <li>Ownership bind without an owner yet → usable (stamp happens on craft/pickup/command).</li>
 * </ul>
 */
public final class OwnerBindPolicy {

    private OwnerBindPolicy() {
    }

    /** True when the actor may use the item under ownership rules. */
    public static boolean mayUse(Optional<BindType> bindType, Optional<UUID> owner, UUID actor) {
        Objects.requireNonNull(bindType, "bindType");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actor, "actor");
        if (bindType.isEmpty() || !bindType.get().enforcesOwnership()) {
            return true;
        }
        if (owner.isEmpty()) {
            return true;
        }
        return owner.get().equals(actor);
    }
}
