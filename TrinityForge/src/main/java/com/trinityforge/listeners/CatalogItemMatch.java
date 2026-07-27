package com.trinityforge.listeners;

import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;
import java.util.Optional;

/** Shared catalog-item matching helpers for smithing / anvil recipe listeners. */
final class CatalogItemMatch {

    private CatalogItemMatch() {
    }

    static int qualityOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return 0;
        }
        return ItemData.of(stack.getItemMeta()).quality();
    }

    static boolean matchesTemplate(ItemStack stack, ItemTemplate template) {
        if (stack == null || stack.getType().isAir() || template == null) {
            return false;
        }
        if (!stack.hasItemMeta()) {
            return stack.getType() == template.material() && template.customModelData() == null;
        }
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        Optional<String> catalogId = data.catalogId();
        if (catalogId.isPresent()) {
            return catalogId.get().equals(template.id());
        }
        if (stack.getType() != template.material()) {
            return false;
        }
        Integer cmd = meta.hasCustomModelData() ? meta.getCustomModelData() : null;
        return Objects.equals(cmd, template.customModelData());
    }
}
