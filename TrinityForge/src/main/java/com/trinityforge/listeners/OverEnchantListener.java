package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Over-cap enchant levels per {@code overenchant:<id>} profile ({@code crafting-features.yml}
 * over-enchant.&lt;id&gt;.enchants), gated by the {@code overenchant:} dynamic gate prefix (2026-07-23
 * 動的ID方式改修 §3). Profile ids are already arbitrary/config-defined (no more special-cased
 * over-enchant-1/2/3); this listener just prefixes the id before the gate lookup.
 */
public final class OverEnchantListener implements Listener {

    private static final String GATE_PREFIX = "overenchant:";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig features;

    public OverEnchantListener(DedicatedEffectsConfig dedicatedEffects, CraftingFeaturesConfig features) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.features = Objects.requireNonNull(features, "features");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!(event.getEnchanter() instanceof Player player)) {
            return;
        }
        Map<Enchantment, Integer> enchants = event.getEnchantsToAdd();
        enchants.replaceAll((ench, level) -> {
            int overMax = maxLevel(player, ench);
            if (overMax <= 0) {
                return level;
            }
            int vanillaMax = ench.getMaxLevel();
            // At vanilla cap, allow +1 toward the profile absolute max (not a full jump).
            if (level >= vanillaMax && overMax > vanillaMax) {
                return Math.min(overMax, level + 1);
            }
            return Math.min(level, overMax);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        ItemStack first = event.getInventory().getFirstItem();
        ItemStack second = event.getInventory().getSecondItem();
        ItemStack result = event.getResult();
        if (first == null || result == null) {
            return;
        }

        boolean changed = false;
        ItemStack out = result.clone();

        Set<Enchantment> candidates = new HashSet<>();
        candidates.addAll(enchantKeys(first));
        if (second != null) {
            candidates.addAll(enchantKeys(second));
        }
        candidates.addAll(enchantKeys(out));

        for (Enchantment ench : candidates) {
            int overMax = maxLevel(player, ench);
            if (overMax <= ench.getMaxLevel()) {
                continue;
            }
            int a = enchantLevel(first, ench);
            int b = second == null ? 0 : enchantLevel(second, ench);
            if (a <= 0 && b <= 0) {
                // Still clamp result if somehow above cap
                int current = enchantLevel(out, ench);
                if (current > overMax) {
                    setEnchantLevel(out, ench, overMax);
                    changed = true;
                }
                continue;
            }
            int combined;
            if (a == b && a > 0) {
                combined = a + 1;
            } else {
                combined = Math.max(a, b);
            }
            combined = Math.min(combined, overMax);
            int current = enchantLevel(out, ench);
            if (combined > current || current > overMax) {
                setEnchantLevel(out, ench, Math.min(combined, overMax));
                changed = true;
            }
        }

        if (changed) {
            event.setResult(out);
        }
    }

    private int maxLevel(Player player, Enchantment ench) {
        return features.overEnchantMaxLevel(id -> dedicatedEffects.isActive(player, GATE_PREFIX + id), ench);
    }

    private static Set<Enchantment> enchantKeys(ItemStack stack) {
        Set<Enchantment> keys = new HashSet<>(stack.getEnchantments().keySet());
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            keys.addAll(storage.getStoredEnchants().keySet());
        }
        return keys;
    }

    private static int enchantLevel(ItemStack stack, Enchantment ench) {
        int level = stack.getEnchantmentLevel(ench);
        if (level > 0) {
            return level;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            return storage.getStoredEnchantLevel(ench);
        }
        return 0;
    }

    private static void setEnchantLevel(ItemStack stack, Enchantment ench, int level) {
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            if (level <= 0) {
                storage.removeStoredEnchant(ench);
            } else {
                storage.addStoredEnchant(ench, level, true);
            }
            stack.setItemMeta(storage);
            return;
        }
        stack.removeEnchantment(ench);
        if (level > 0) {
            stack.addUnsafeEnchantment(ench, level);
        }
    }
}
