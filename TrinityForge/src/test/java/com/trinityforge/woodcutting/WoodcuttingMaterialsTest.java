package com.trinityforge.woodcutting;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WoodcuttingMaterials}: log/leaves/axe classification by material-name suffix. */
class WoodcuttingMaterialsTest {

    @Test
    void logFamilyMaterialsAreRecognised() {
        assertTrue(WoodcuttingMaterials.isLog(Material.OAK_LOG));
        assertTrue(WoodcuttingMaterials.isLog(Material.STRIPPED_OAK_LOG));
        assertTrue(WoodcuttingMaterials.isLog(Material.OAK_WOOD));
        assertTrue(WoodcuttingMaterials.isLog(Material.WARPED_STEM));
        assertTrue(WoodcuttingMaterials.isLog(Material.CRIMSON_HYPHAE));
    }

    @Test
    void nonLogMaterialsAreNotLogs() {
        assertFalse(WoodcuttingMaterials.isLog(Material.OAK_LEAVES));
        assertFalse(WoodcuttingMaterials.isLog(Material.OAK_SAPLING));
        assertFalse(WoodcuttingMaterials.isLog(Material.STONE));
        assertFalse(WoodcuttingMaterials.isLog(null));
    }

    @Test
    void leavesFamilyMaterialsAreRecognised() {
        assertTrue(WoodcuttingMaterials.isLeaves(Material.OAK_LEAVES));
        assertTrue(WoodcuttingMaterials.isLeaves(Material.AZALEA_LEAVES));
        assertFalse(WoodcuttingMaterials.isLeaves(Material.OAK_LOG));
        assertFalse(WoodcuttingMaterials.isLeaves(null));
    }

    @Test
    void axeMaterialsAreRecognised() {
        assertTrue(WoodcuttingMaterials.isAxe(Material.DIAMOND_AXE));
        assertTrue(WoodcuttingMaterials.isAxe(Material.WOODEN_AXE));
        assertFalse(WoodcuttingMaterials.isAxe(Material.DIAMOND_PICKAXE));
        assertFalse(WoodcuttingMaterials.isAxe(Material.AIR));
        assertFalse(WoodcuttingMaterials.isAxe(null));
    }
}
