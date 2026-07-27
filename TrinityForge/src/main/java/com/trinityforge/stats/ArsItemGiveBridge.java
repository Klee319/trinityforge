package com.trinityforge.stats;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft bridge to ArsPaper {@code ItemRegistry} for {@code /tf give}.
 * Apparatus IDs (pedestal / jars / sourcelinks / …) stay on {@code /ars give}.
 */
public final class ArsItemGiveBridge {

    private static final Set<String> APPARATUS = Set.of(
            "pedestal", "ritual_core", "scribing_table", "source_jar", "creative_source_jar");

    private ArsItemGiveBridge() {
    }

    public static boolean isApparatus(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        String id = itemId.trim().toLowerCase(Locale.ROOT);
        return APPARATUS.contains(id) || id.endsWith("_sourcelink");
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("ArsPaper") != null;
    }

    public static List<String> listPlayerItemIds() {
        List<String> ids = new ArrayList<>();
        if (!isAvailable()) {
            return ids;
        }
        try {
            Plugin ars = Bukkit.getPluginManager().getPlugin("ArsPaper");
            Object registry = ars.getClass().getMethod("getItemRegistry").invoke(ars);
            Collection<?> all = (Collection<?>) registry.getClass().getMethod("getAll").invoke(registry);
            for (Object item : all) {
                String id = (String) item.getClass().getMethod("getItemId").invoke(item);
                if (id != null && !isApparatus(id)) {
                    ids.add(id);
                }
            }
        } catch (ReflectiveOperationException ex) {
            Logger.getLogger(ArsItemGiveBridge.class.getName())
                    .log(Level.FINE, "[ars-give-bridge] list failed", ex);
        }
        return ids;
    }

    public static Optional<ItemStack> create(String itemId) {
        if (!isAvailable() || itemId == null || isApparatus(itemId)) {
            return Optional.empty();
        }
        try {
            Plugin ars = Bukkit.getPluginManager().getPlugin("ArsPaper");
            Object registry = ars.getClass().getMethod("getItemRegistry").invoke(ars);
            Method get = registry.getClass().getMethod("get", String.class);
            Object opt = get.invoke(registry, itemId);
            if (opt instanceof Optional<?> optional) {
                if (optional.isEmpty()) {
                    return Optional.empty();
                }
                Object customItem = optional.get();
                ItemStack stack = (ItemStack) customItem.getClass().getMethod("createItemStack").invoke(customItem);
                return Optional.ofNullable(stack);
            }
        } catch (ReflectiveOperationException ex) {
            Logger.getLogger(ArsItemGiveBridge.class.getName())
                    .log(Level.WARNING, "[ars-give-bridge] create failed for " + itemId, ex);
        }
        return Optional.empty();
    }

    /**
     * Ars {@code BaseCustomItem#isQualityStamped()} — spellbooks/catalysts など品質刻印対象。
     */
    public static boolean isQualityStamped(String itemId) {
        if (!isAvailable() || itemId == null || isApparatus(itemId)) {
            return false;
        }
        try {
            Plugin ars = Bukkit.getPluginManager().getPlugin("ArsPaper");
            Object registry = ars.getClass().getMethod("getItemRegistry").invoke(ars);
            Method get = registry.getClass().getMethod("get", String.class);
            Object opt = get.invoke(registry, itemId);
            if (opt instanceof Optional<?> optional && optional.isPresent()) {
                Object customItem = optional.get();
                Object stamped = customItem.getClass().getMethod("isQualityStamped").invoke(customItem);
                return Boolean.TRUE.equals(stamped);
            }
        } catch (ReflectiveOperationException ex) {
            Logger.getLogger(ArsItemGiveBridge.class.getName())
                    .log(Level.FINE, "[ars-give-bridge] isQualityStamped failed for " + itemId, ex);
        }
        return false;
    }
}
