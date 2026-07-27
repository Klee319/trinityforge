package com.trinityforge.pdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindTypeTest {

    @Test
    @DisplayName("storageValue round-trips back to the same enum constant")
    void storageValue_roundTrips() {
        for (BindType type : BindType.values()) {
            Optional<BindType> parsed = BindType.fromStorage(type.storageValue());
            assertEquals(Optional.of(type), parsed);
        }
    }

    @Test
    @DisplayName("fromStorage returns empty for null, blank, and unknown values")
    void fromStorage_degradesOnBadInput() {
        assertTrue(BindType.fromStorage(null).isEmpty());
        assertTrue(BindType.fromStorage("").isEmpty());
        assertTrue(BindType.fromStorage("   ").isEmpty());
        assertTrue(BindType.fromStorage("NOT_A_BIND_TYPE").isEmpty());
    }

    @Test
    @DisplayName("storageValue matches the stable identifiers")
    void storageValue_isStableIdentifier() {
        assertEquals("SOULBOUND", BindType.SOULBOUND.storageValue());
        assertEquals("TRADEABLE", BindType.TRADEABLE.storageValue());
        assertEquals("OWNER_BOUND", BindType.OWNER_BOUND.storageValue());
    }

    @Test
    @DisplayName("legacy MATERIAL_TRADEABLE aliases to TRADEABLE")
    void fromStorage_acceptsLegacyTradeable() {
        assertEquals(Optional.of(BindType.TRADEABLE),
                BindType.fromStorage(BindType.LEGACY_TRADEABLE));
        assertEquals(Optional.of(BindType.TRADEABLE),
                BindType.fromStorage("material_tradeable"));
    }

    @Test
    @DisplayName("ownership helpers")
    void ownershipHelpers() {
        assertTrue(BindType.SOULBOUND.enforcesOwnership());
        assertTrue(BindType.OWNER_BOUND.enforcesOwnership());
        assertFalse(BindType.TRADEABLE.enforcesOwnership());
        assertTrue(BindType.SOULBOUND.autoStampsOwner());
        assertFalse(BindType.OWNER_BOUND.autoStampsOwner());
        assertFalse(BindType.TRADEABLE.autoStampsOwner());
    }
}
