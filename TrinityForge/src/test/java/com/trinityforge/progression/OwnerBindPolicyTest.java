package com.trinityforge.progression;

import com.trinityforge.pdc.BindType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerBindPolicyTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("tradeable and unbound items are usable by anyone")
    void tradeableAlwaysUsable() {
        assertTrue(OwnerBindPolicy.mayUse(Optional.empty(), Optional.empty(), OTHER));
        assertTrue(OwnerBindPolicy.mayUse(Optional.of(BindType.TRADEABLE), Optional.of(OWNER), OTHER));
    }

    @Test
    @DisplayName("soulbound/owner-bound without owner yet remain usable")
    void unboundOwnershipStillUsable() {
        assertTrue(OwnerBindPolicy.mayUse(Optional.of(BindType.SOULBOUND), Optional.empty(), OTHER));
        assertTrue(OwnerBindPolicy.mayUse(Optional.of(BindType.OWNER_BOUND), Optional.empty(), OTHER));
    }

    @Test
    @DisplayName("owned soulbound/owner-bound deny non-owners")
    void ownedDeniesNonOwner() {
        assertTrue(OwnerBindPolicy.mayUse(Optional.of(BindType.SOULBOUND), Optional.of(OWNER), OWNER));
        assertFalse(OwnerBindPolicy.mayUse(Optional.of(BindType.SOULBOUND), Optional.of(OWNER), OTHER));
        assertFalse(OwnerBindPolicy.mayUse(Optional.of(BindType.OWNER_BOUND), Optional.of(OWNER), OTHER));
    }
}
