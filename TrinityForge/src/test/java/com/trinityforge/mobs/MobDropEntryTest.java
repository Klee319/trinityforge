package com.trinityforge.mobs;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobDropEntryTest {

    @Test
    void validEntryConstructsWithNullQualityMeaningLevelDriven() {
        MobDropEntry entry = new MobDropEntry(Material.ROTTEN_FLESH, 0.1, 1, 2, null);
        assertEquals(Material.ROTTEN_FLESH, entry.material());
        assertEquals(0.1, entry.chance());
        assertEquals(1, entry.min());
        assertEquals(2, entry.max());
        assertNull(entry.quality());
    }

    @Test
    void chanceOutOfRangeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MobDropEntry(Material.IRON_INGOT, -0.1, 0, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new MobDropEntry(Material.IRON_INGOT, 1.1, 0, 1, null));
    }

    @Test
    void negativeMinThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MobDropEntry(Material.IRON_INGOT, 0.5, -1, 2, null));
    }

    @Test
    void minGreaterThanMaxThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MobDropEntry(Material.IRON_INGOT, 0.5, 5, 2, null));
    }

    @Test
    void fixedQualityIsPreserved() {
        MobDropEntry entry = new MobDropEntry(Material.IRON_SWORD, 0.2, 1, 1, 4);
        assertEquals(4, entry.quality());
    }

    // --- 2026-08-01 U13: custom:<id> (material/catalogId は排他必須) ---

    @Test
    void catalogEntryCarriesTheIdAndNoMaterial() {
        MobDropEntry entry = MobDropEntry.ofCatalog("tf_scrap", 0.1, 1, 2, null);
        assertTrue(entry.isCustom());
        assertEquals("tf_scrap", entry.catalogId());
        assertNull(entry.material());
    }

    @Test
    void materialEntryIsNotCustom() {
        assertFalse(MobDropEntry.ofMaterial(Material.BONE, 0.1, 1, 1, null).isCustom());
    }

    @Test
    void neitherOrBothItemFieldsThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new MobDropEntry(null, null, 0.1, 1, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MobDropEntry(Material.BONE, "tf_scrap", 0.1, 1, 1, null));
    }

    @Test
    void blankCatalogIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new MobDropEntry(null, "  ", 0.1, 1, 1, null));
    }
}
