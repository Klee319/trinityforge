package com.trinityforge.pdc;

import java.util.Locale;
import java.util.Optional;

/**
 * Ownership constraint stamped on an item.
 *
 * <ul>
 *   <li>{@link #SOULBOUND} — forced bind to the crafter (when obtained at create) or the first
 *       picker otherwise. Non-owners cannot use the item.</li>
 *   <li>{@link #TRADEABLE} — cannot be bound (default for materials).</li>
 *   <li>{@link #OWNER_BOUND} — owner set only via admin command; non-owners cannot use.</li>
 * </ul>
 *
 * <p>{@link #storageValue()} is the stable string persisted in the PDC. Legacy
 * {@code MATERIAL_TRADEABLE} is accepted by {@link #fromStorage(String)} as an alias of
 * {@link #TRADEABLE}.
 */
public enum BindType {
    SOULBOUND("SOULBOUND"),
    TRADEABLE("TRADEABLE"),
    OWNER_BOUND("OWNER_BOUND");

    /** Legacy PDC / YAML value accepted as {@link #TRADEABLE}. */
    public static final String LEGACY_TRADEABLE = "MATERIAL_TRADEABLE";

    private final String storageKey;

    BindType(String storageKey) {
        this.storageKey = storageKey;
    }

    /** The stable identifier stored in the PDC. */
    public String storageValue() {
        return storageKey;
    }

    /** True when this bind type may carry an owner and enforce non-owner use denial. */
    public boolean enforcesOwnership() {
        return this == SOULBOUND || this == OWNER_BOUND;
    }

    /** True when first-acquirer auto-stamp is allowed (SOULBOUND only; OWNER_BOUND is command-only). */
    public boolean autoStampsOwner() {
        return this == SOULBOUND;
    }

    /** Parses a stored value, returning empty for null/blank/unknown input. */
    public static Optional<BindType> fromStorage(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        String key = stored.trim().toUpperCase(Locale.ROOT);
        if (LEGACY_TRADEABLE.equals(key)) {
            return Optional.of(TRADEABLE);
        }
        for (BindType type : values()) {
            if (type.storageKey.equals(key)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
