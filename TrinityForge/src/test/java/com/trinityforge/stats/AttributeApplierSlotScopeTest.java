package com.trinityforge.stats;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttributeApplierSlotScopeTest {

    @Test
    void weaponUsesMainhandWhenOffhandStatsAreDisabled() {
        assertEquals(EquipmentSlotGroup.MAINHAND,
                AttributeApplier.slotGroupFor(Material.DIAMOND_SWORD, false));
    }

    @Test
    void weaponUsesBothHandsWhenOffhandStatsAreEnabled() {
        assertEquals(EquipmentSlotGroup.HAND,
                AttributeApplier.slotGroupFor(Material.DIAMOND_SWORD, true));
    }

    @Test
    void armorSlotIsUnaffectedByOffhandSetting() {
        assertEquals(EquipmentSlotGroup.CHEST,
                AttributeApplier.slotGroupFor(Material.DIAMOND_CHESTPLATE, false));
        assertEquals(EquipmentSlotGroup.CHEST,
                AttributeApplier.slotGroupFor(Material.DIAMOND_CHESTPLATE, true));
    }

    @Test
    void configClassifiedWeaponOverridesUncategorizedBaseMaterial() {
        assertEquals(EquipmentSlotGroup.MAINHAND,
                AttributeApplier.slotGroupFor(Material.BOOK, false, true));
        assertEquals(EquipmentSlotGroup.HAND,
                AttributeApplier.slotGroupFor(Material.BOOK, true, true));
    }
}
