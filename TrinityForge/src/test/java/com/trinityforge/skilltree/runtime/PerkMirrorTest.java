package com.trinityforge.skilltree.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PerkMirror}: the pure canonicalization + change-detection that drives the PDC
 * held-perk mirror ({@link PerkMirrorService}). No Bukkit — the reconciliation is fully offline-verifiable.
 */
class PerkMirrorTest {

    @Nested
    @DisplayName("canonical")
    class Canonical {

        @Test
        @DisplayName("sorts the perk ids so equal sets always yield an equal list")
        void sortsDeterministically() {
            List<String> result = PerkMirror.canonical(Set.of(
                    "woodcutting_perk_e", "arsmagic_perk_a", "arsmagic_perk_c"));
            assertEquals(List.of("arsmagic_perk_a", "arsmagic_perk_c", "woodcutting_perk_e"), result);
        }

        @Test
        @DisplayName("drops blank and empty ids")
        void dropsBlanks() {
            List<String> result = PerkMirror.canonical(Set.of("arsmagic_perk_a", "", "   "));
            assertEquals(List.of("arsmagic_perk_a"), result);
        }

        @Test
        @DisplayName("empty set yields empty list")
        void emptyYieldsEmpty() {
            assertTrue(PerkMirror.canonical(Set.of()).isEmpty());
        }
    }

    @Nested
    @DisplayName("needsWrite")
    class NeedsWrite {

        @Test
        @DisplayName("false when stored equals canonical (steady state pays no PDC write)")
        void falseWhenEqual() {
            List<String> both = List.of("arsmagic_perk_a", "arsmagic_perk_c");
            assertFalse(PerkMirror.needsWrite(both, both));
        }

        @Test
        @DisplayName("true when a newly-unlocked perk is missing from the stored list")
        void trueWhenPerkAdded() {
            List<String> stored = List.of("arsmagic_perk_a");
            List<String> canonical = PerkMirror.canonical(Set.of("arsmagic_perk_a", "arsmagic_perk_c"));
            assertTrue(PerkMirror.needsWrite(stored, canonical));
        }

        @Test
        @DisplayName("true when a perk was lost (stored has one the unlocked set no longer does)")
        void trueWhenPerkRemoved() {
            List<String> stored = List.of("arsmagic_perk_a", "arsmagic_perk_c");
            List<String> canonical = PerkMirror.canonical(Set.of("arsmagic_perk_a"));
            assertTrue(PerkMirror.needsWrite(stored, canonical));
        }

        @Test
        @DisplayName("empty-vs-empty is no write")
        void emptyVsEmptyNoWrite() {
            assertFalse(PerkMirror.needsWrite(List.of(), PerkMirror.canonical(Set.of())));
        }
    }
}
