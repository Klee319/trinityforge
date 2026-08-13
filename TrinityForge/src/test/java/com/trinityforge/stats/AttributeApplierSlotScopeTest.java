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

    // --- レーンC(2026-08-13): ANY分類の素材にもoffhand-stats-applyの門を効かせる ---

    @Test
    void anyCategoryMaterialsFallToMainhandWhenOffhandStatsAreDisabled() {
        assertEquals(EquipmentSlotGroup.MAINHAND, AttributeApplier.slotGroupFor(Material.SHIELD, false));
        assertEquals(EquipmentSlotGroup.MAINHAND, AttributeApplier.slotGroupFor(Material.FISHING_ROD, false));
        assertEquals(EquipmentSlotGroup.MAINHAND, AttributeApplier.slotGroupFor(Material.SHEARS, false));
        assertEquals(EquipmentSlotGroup.MAINHAND, AttributeApplier.slotGroupFor(Material.FLINT_AND_STEEL, false));
        assertEquals(EquipmentSlotGroup.MAINHAND, AttributeApplier.slotGroupFor(Material.BOOK, false));
        assertEquals(EquipmentSlotGroup.MAINHAND, AttributeApplier.slotGroupFor(Material.BREAD, false));
        assertEquals(EquipmentSlotGroup.MAINHAND, AttributeApplier.slotGroupFor(Material.STONE, false));
    }

    @Test
    void anyCategoryMaterialsUseBothHandsWhenOffhandStatsAreEnabled() {
        assertEquals(EquipmentSlotGroup.HAND, AttributeApplier.slotGroupFor(Material.SHIELD, true));
        assertEquals(EquipmentSlotGroup.HAND, AttributeApplier.slotGroupFor(Material.FISHING_ROD, true));
        assertEquals(EquipmentSlotGroup.HAND, AttributeApplier.slotGroupFor(Material.SHEARS, true));
        assertEquals(EquipmentSlotGroup.HAND, AttributeApplier.slotGroupFor(Material.FLINT_AND_STEEL, true));
        assertEquals(EquipmentSlotGroup.HAND, AttributeApplier.slotGroupFor(Material.BOOK, true));
        assertEquals(EquipmentSlotGroup.HAND, AttributeApplier.slotGroupFor(Material.BREAD, true));
        assertEquals(EquipmentSlotGroup.HAND, AttributeApplier.slotGroupFor(Material.STONE, true));
    }
}
