package com.trinityforge.stats;

import org.bukkit.Material;

import java.util.Map;
import java.util.Set;

/**
 * Thread-slot (スレッド枠) resolution helpers: crafter perk bonus baked at craft time plus
 * category caps from {@code progression/crafting-features.yml}.
 */
public final class ThreadSlotPolicy {

    private static final String THREAD_SLOTS_KEY = StatKeys.canonical("thread-slots");

    private ThreadSlotPolicy() {
    }

    /** Adds {@code bonus} to the derived {@code thread-slots} stat (floor at apply time elsewhere). */
    public static void applyCraftBonus(Map<String, Double> stats, int bonus) {
        if (bonus <= 0) {
            return;
        }
        stats.merge(THREAD_SLOTS_KEY, (double) bonus, Double::sum);
    }

    /**
     * Clamps or clears {@code thread-slots} for {@code material} using {@code maxByCategory}.
     * When every matching category cap is {@code <= 0}, the stat is removed (non-armor gear).
     */
    public static void applyCategoryCap(Map<String, Double> stats, Material material,
                                        Map<String, Integer> maxByCategory) {
        if (stats == null || material == null || maxByCategory == null || maxByCategory.isEmpty()) {
            return;
        }
        int cap = capFor(material, maxByCategory);
        if (cap <= 0) {
            stats.remove(THREAD_SLOTS_KEY);
            return;
        }
        double current = stats.getOrDefault(THREAD_SLOTS_KEY, 0.0);
        stats.put(THREAD_SLOTS_KEY, (double) Math.min(cap, Math.max(0, (int) Math.floor(current))));
    }

    static int capFor(Material material, Map<String, Integer> maxByCategory) {
        Set<String> categories = EquipmentSlotResolver.statCategories(material);
        int cap = 0;
        for (String category : categories) {
            cap = Math.max(cap, maxByCategory.getOrDefault(category, 0));
        }
        return cap;
    }
}
