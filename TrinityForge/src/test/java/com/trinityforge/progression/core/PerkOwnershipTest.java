package com.trinityforge.progression.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Effective-set semantics tests for {@link PerkOwnership}. No MockBukkit required. */
class PerkOwnershipTest {

    @Test
    void empty_ownsNothing() {
        assertFalse(PerkOwnership.EMPTY.owns("any_perk"));
        assertEquals(0, PerkOwnership.EMPTY.size());
    }

    @Test
    void withUnlocked_addsToEffectiveSet() {
        PerkOwnership p = PerkOwnership.EMPTY.withUnlocked("perk_a");
        assertTrue(p.owns("perk_a"));
        assertEquals(1, p.size());
    }

    @Test
    void withUnlocked_isImmutable_originalUnchanged() {
        PerkOwnership original = PerkOwnership.EMPTY;
        original.withUnlocked("perk_a"); // result discarded intentionally
        assertFalse(original.owns("perk_a"), "original must not be mutated");
        assertEquals(0, original.size());
    }

    @Test
    void withUnlocked_idempotent() {
        PerkOwnership p = PerkOwnership.EMPTY.withUnlocked("perk_a").withUnlocked("perk_a");
        assertEquals(1, p.size());
    }

    @Test
    void withUnlocked_multiplePerks() {
        PerkOwnership p = PerkOwnership.EMPTY
                .withUnlocked("perk_a")
                .withUnlocked("perk_b")
                .withUnlocked("perk_c");
        assertTrue(p.owns("perk_a"));
        assertTrue(p.owns("perk_b"));
        assertTrue(p.owns("perk_c"));
        assertEquals(3, p.size());
    }

    @Test
    void withRevoked_removesFromEffectiveSet() {
        PerkOwnership p = PerkOwnership.EMPTY
                .withUnlocked("perk_a")
                .withUnlocked("perk_b")
                .withRevoked("perk_a");
        assertFalse(p.owns("perk_a"));
        assertTrue(p.owns("perk_b"));
        assertEquals(1, p.size());
    }

    @Test
    void withRevoked_nonExistentIsNoop_returnsSameRef() {
        PerkOwnership p = PerkOwnership.EMPTY;
        assertSame(p, p.withRevoked("nonexistent"));
    }

    @Test
    void withRevoked_alreadyOwned_returnsSameRef() {
        PerkOwnership p = PerkOwnership.EMPTY.withUnlocked("perk_a");
        PerkOwnership same = p.withUnlocked("perk_a");
        assertSame(p, same);
    }

    @Test
    void effectiveSet_isUnmodifiable() {
        PerkOwnership p = PerkOwnership.EMPTY.withUnlocked("perk_a");
        assertThrows(UnsupportedOperationException.class, () -> p.effectiveSet().add("perk_x"));
    }

    @Test
    void effectiveSet_containsAllUnlockedPerks() {
        PerkOwnership p = PerkOwnership.EMPTY.withUnlocked("x").withUnlocked("y");
        assertTrue(p.effectiveSet().containsAll(Set.of("x", "y")));
    }

    @Test
    void owns_nullReturnsFalse() {
        assertFalse(PerkOwnership.EMPTY.owns(null));
        assertFalse(PerkOwnership.EMPTY.withUnlocked("perk_a").owns(null));
    }

    @Test
    void of_fromSet_equivalentToStepByStep() {
        Set<String> ids = Set.of("a", "b");
        PerkOwnership fromSet = PerkOwnership.of(ids);
        PerkOwnership stepByStep = PerkOwnership.EMPTY.withUnlocked("a").withUnlocked("b");
        assertEquals(fromSet, stepByStep);
    }

    @Test
    void of_nullOrEmptySet_returnsEmpty() {
        assertSame(PerkOwnership.EMPTY, PerkOwnership.of(null));
        assertSame(PerkOwnership.EMPTY, PerkOwnership.of(Set.of()));
    }

    @Test
    void equals_symmetric() {
        PerkOwnership p1 = PerkOwnership.of(Set.of("x", "y"));
        PerkOwnership p2 = PerkOwnership.EMPTY.withUnlocked("y").withUnlocked("x");
        assertEquals(p1, p2);
        assertEquals(p2, p1);
        assertEquals(p1.hashCode(), p2.hashCode());
    }

    @Test
    void revokeAll_resultsInEmpty() {
        PerkOwnership p = PerkOwnership.of(Set.of("a", "b")).withRevoked("a").withRevoked("b");
        assertEquals(0, p.size());
        assertEquals(PerkOwnership.EMPTY, p);
    }
}
