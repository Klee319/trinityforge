package com.trinityforge.stats;

import org.bukkit.inventory.EquipmentSlotGroup;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * CMB-19 regression: an item with no rollSeed always got the SAME modifier key suffix ({@code .nosd}),
 * regardless of which slot it occupies. Two rollSeed-less pieces of gear granting the same stat key
 * (e.g. a helmet and a chestplate both authored with a plain {@code max-health} bonus, no roll table
 * entry) therefore produced attribute modifiers with an identical {@link org.bukkit.NamespacedKey} —
 * vanilla/Bukkit treats same-key modifiers as one instance, so only one piece's bonus actually landed
 * when both were worn simultaneously.
 *
 * <p>Fix: the ".nosd" suffix now includes the resolved {@code EquipmentSlotGroup}'s own key
 * (e.g. "head"/"chest"), unique per apply() call because only one item can occupy a given non-HAND
 * slot at a time.
 *
 * <p>Tests {@link AttributeApplier#rollSeedSuffix} directly (Bukkit-light, no {@code ItemMeta}
 * round-trip): a full {@code apply()} + {@code ItemMeta#getAttributeModifiers()} round-trip is not
 * exercisable under MockBukkit here ({@code getAttributeModifiers()} throws
 * {@code UnimplementedOperationException}), matching the same constraint documented on
 * {@code AttributeApplierMissingDefaultsTest}.
 */
class AttributeApplierNoRollSeedSlotUniquenessTest {

    @Test
    void helmetAndChestplateWithoutRollSeedGetDistinctSuffixesForTheSameStat() {
        String helmetSuffix = AttributeApplier.rollSeedSuffix(Optional.empty(), EquipmentSlotGroup.HEAD);
        String chestSuffix = AttributeApplier.rollSeedSuffix(Optional.empty(), EquipmentSlotGroup.CHEST);

        assertNotEquals(helmetSuffix, chestSuffix,
                "CMB-19: rollSeed-less items in different slots must not collide on modifier key suffix");
    }

    @Test
    void sameSlotWithoutRollSeedIsIdempotentAcrossReapplies() {
        // Re-applying the SAME slot (e.g. the same helmet re-processed) must keep producing the same
        // suffix, not grow a new one each time.
        String first = AttributeApplier.rollSeedSuffix(Optional.empty(), EquipmentSlotGroup.HEAD);
        String second = AttributeApplier.rollSeedSuffix(Optional.empty(), EquipmentSlotGroup.HEAD);

        assertEquals(first, second, "re-applying the same slot must be idempotent");
    }

    @Test
    void rollSeedBearingItemsKeepTheExistingHexSuffixUnaffectedByCmb19() {
        // Regression guard: items WITH a rollSeed must be entirely unaffected by the CMB-19 fix —
        // their suffix is still the plain per-item hex seed, with no slot mixed in.
        String suffix = AttributeApplier.rollSeedSuffix(Optional.of(0xABCDEFL), EquipmentSlotGroup.HEAD);
        assertEquals(".abcdef", suffix);

        String otherSlotSameSeed =
                AttributeApplier.rollSeedSuffix(Optional.of(0xABCDEFL), EquipmentSlotGroup.CHEST);
        assertEquals(suffix, otherSlotSameSeed,
                "a rollSeed-bearing item's suffix must be slot-independent (identity already unique via seed)");
    }

    @Test
    void noRollSeedSuffixEndsWithTheSlotGroupsOwnKey() {
        String suffix = AttributeApplier.rollSeedSuffix(Optional.empty(), EquipmentSlotGroup.MAINHAND);
        assertEquals(".nosd." + EquipmentSlotGroup.MAINHAND, suffix);
    }
}
