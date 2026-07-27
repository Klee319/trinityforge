package com.trinityforge.progression.core;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable effective perk ownership set for a single player.
 *
 * <p>"Effective" follows the same contract as the Valhalla side
 * ({@code (unlocked ∪ permanentlyUnlocked) − fakeUnlocked − permanentlyLocked}) but simplified
 * for the native persistence layer: TrinityForge owns all unlock state, so there are no
 * fake-unlock or permanently-locked categories. The effective set is exactly the set of perk
 * IDs that have been explicitly unlocked and not yet revoked.
 *
 * <p>All mutation methods return new instances; the original is never modified.
 */
public final class PerkOwnership {

    /** An empty perk ownership, useful as the starting state for a new player. */
    public static final PerkOwnership EMPTY = new PerkOwnership(Set.of());

    private final Set<String> effectivePerkIds;

    private PerkOwnership(Set<String> ids) {
        this.effectivePerkIds = Set.copyOf(ids);
    }

    /** Creates ownership from an existing set of perk IDs. */
    public static PerkOwnership of(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return EMPTY;
        return new PerkOwnership(ids);
    }

    /**
     * Returns {@code true} if {@code perkId} is in the effective set.
     * {@code null} perkId always returns {@code false}.
     */
    public boolean owns(String perkId) {
        return perkId != null && effectivePerkIds.contains(perkId);
    }

    /**
     * Returns a new {@link PerkOwnership} with {@code perkId} added to the effective set.
     * If the perk is already owned, returns {@code this} unchanged.
     */
    public PerkOwnership withUnlocked(String perkId) {
        Objects.requireNonNull(perkId, "perkId");
        if (effectivePerkIds.contains(perkId)) return this;
        Set<String> next = new HashSet<>(effectivePerkIds);
        next.add(perkId);
        return new PerkOwnership(next);
    }

    /**
     * Returns a new {@link PerkOwnership} with {@code perkId} removed from the effective set.
     * If the perk is not owned, returns {@code this} unchanged.
     */
    public PerkOwnership withRevoked(String perkId) {
        if (perkId == null || !effectivePerkIds.contains(perkId)) return this;
        Set<String> next = new HashSet<>(effectivePerkIds);
        next.remove(perkId);
        return new PerkOwnership(next);
    }

    /** The unmodifiable effective set of owned perk IDs. */
    public Set<String> effectiveSet() {
        return effectivePerkIds;
    }

    public int size() {
        return effectivePerkIds.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PerkOwnership other)) return false;
        return effectivePerkIds.equals(other.effectivePerkIds);
    }

    @Override
    public int hashCode() {
        return effectivePerkIds.hashCode();
    }

    @Override
    public String toString() {
        return "PerkOwnership" + effectivePerkIds;
    }
}
