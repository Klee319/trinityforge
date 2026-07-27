package com.trinityforge.stats;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ThreadSlotPolicyTest {

    private static final Map<String, Integer> DEFAULT_CAPS = Map.of(
            EquipmentSlotResolver.CATEGORY_ARMOR, 5,
            EquipmentSlotResolver.CATEGORY_WEAPON, 0,
            EquipmentSlotResolver.CATEGORY_TOOL, 0,
            EquipmentSlotResolver.CATEGORY_OTHER, 0);

    @Test
    void applyCraftBonusAddsToThreadSlots() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put(StatKeys.canonical("thread-slots"), 2.0);
        ThreadSlotPolicy.applyCraftBonus(stats, 1);
        assertEquals(3.0, stats.get(StatKeys.canonical("thread-slots")));
    }

    @Test
    void armorCapClampsThreadSlots() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put(StatKeys.canonical("thread-slots"), 7.0);
        ThreadSlotPolicy.applyCategoryCap(stats, Material.DIAMOND_CHESTPLATE, DEFAULT_CAPS);
        assertEquals(5.0, stats.get(StatKeys.canonical("thread-slots")));
    }

    @Test
    void weaponCapRemovesThreadSlots() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put(StatKeys.canonical("thread-slots"), 2.0);
        ThreadSlotPolicy.applyCategoryCap(stats, Material.DIAMOND_SWORD, DEFAULT_CAPS);
        assertFalse(stats.containsKey(StatKeys.canonical("thread-slots")));
    }
}
