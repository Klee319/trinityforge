package com.trinityforge.farming;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FarmingCropCatalog}: crop membership and crop->seed mapping. */
class FarmingCropCatalogTest {

    @Test
    void recognizesAllFiveTargetCrops() {
        assertTrue(FarmingCropCatalog.isCrop(Material.WHEAT));
        assertTrue(FarmingCropCatalog.isCrop(Material.CARROTS));
        assertTrue(FarmingCropCatalog.isCrop(Material.POTATOES));
        assertTrue(FarmingCropCatalog.isCrop(Material.BEETROOTS));
        assertTrue(FarmingCropCatalog.isCrop(Material.NETHER_WART));
    }

    @Test
    void rejectsNonCropMaterials() {
        assertFalse(FarmingCropCatalog.isCrop(Material.STONE));
        assertFalse(FarmingCropCatalog.isCrop(Material.SUGAR_CANE));
        assertFalse(FarmingCropCatalog.isCrop(null));
    }

    @Test
    void wheatSeedsAreDedicatedSeedItem() {
        assertEquals(Material.WHEAT_SEEDS, FarmingCropCatalog.seedMaterial(Material.WHEAT));
    }

    @Test
    void beetrootSeedsAreDedicatedSeedItem() {
        assertEquals(Material.BEETROOT_SEEDS, FarmingCropCatalog.seedMaterial(Material.BEETROOTS));
    }

    @Test
    void carrotsPotatoesNetherWartAreSelfSeeding() {
        assertEquals(Material.CARROT, FarmingCropCatalog.seedMaterial(Material.CARROTS));
        assertEquals(Material.POTATO, FarmingCropCatalog.seedMaterial(Material.POTATOES));
        assertEquals(Material.NETHER_WART, FarmingCropCatalog.seedMaterial(Material.NETHER_WART));
    }

    @Test
    void unknownCropHasNoSeedMaterial() {
        assertNull(FarmingCropCatalog.seedMaterial(Material.STONE));
    }
}
