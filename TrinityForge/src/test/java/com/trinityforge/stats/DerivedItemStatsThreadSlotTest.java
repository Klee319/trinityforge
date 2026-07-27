package com.trinityforge.stats;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DerivedItemStatsThreadSlotTest {

    private static final Map<String, Integer> ARMOR_CAP = Map.of(
            EquipmentSlotResolver.CATEGORY_ARMOR, 5,
            EquipmentSlotResolver.CATEGORY_WEAPON, 0,
            EquipmentSlotResolver.CATEGORY_TOOL, 0,
            EquipmentSlotResolver.CATEGORY_OTHER, 0);

    @Test
    void craftBonusAndCapApplyAfterProfileOverlay() {
        String key = StatKeys.canonical("thread-slots");
        Map<String, Double> stats = new LinkedHashMap<>();
        ItemStatProfile profile = new ItemStatProfile(
                Map.of(key, 4.0),
                Map.of(key, 1.0),
                Map.of());
        DerivedItemStats.applyProfile(stats, profile, 2);
        ThreadSlotPolicy.applyCraftBonus(stats, 2);
        ThreadSlotPolicy.applyCategoryCap(stats, Material.DIAMOND_CHESTPLATE, ARMOR_CAP);

        assertEquals(5.0, stats.get(key));
    }

    @Test
    void weaponCapRemovesThreadSlotsAfterCraftBonus() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put(StatKeys.canonical("thread-slots"), 2.0);
        ThreadSlotPolicy.applyCraftBonus(stats, 1);
        ThreadSlotPolicy.applyCategoryCap(stats, Material.DIAMOND_SWORD, ARMOR_CAP);
        assertFalse(stats.containsKey(StatKeys.canonical("thread-slots")));
    }
}
