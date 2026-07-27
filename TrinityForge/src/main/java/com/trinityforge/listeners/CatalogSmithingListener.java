package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.PreviewRollSeeds;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Catalog {@code recipe.method: netherite} on the smithing table.
 *
 * <p>Template = netherite upgrade smithing template, base = {@code source-item},
 * addition = netherite ingot; result = this catalog entry with source quality.
 * Combine recipes are handled by {@link CatalogAnvilListener} on the anvil.
 */
public final class CatalogSmithingListener implements Listener {

    private final ItemCatalogConfig itemCatalog;
    private final ItemFactory itemFactory;

    public CatalogSmithingListener(ItemCatalogConfig itemCatalog, ItemFactory itemFactory) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepare(PrepareSmithingEvent event) {
        Match match = match(event.getInventory());
        if (match == null) {
            return;
        }
        int quality = CatalogItemMatch.qualityOf(match.base());
        ItemStack result = itemFactory.create(match.resultTemplate(), PreviewRollSeeds.SMITHING, quality);
        CatalogIdentity.ensure(result, itemCatalog);
        event.setResult(result);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmith(SmithItemEvent event) {
        Match match = match(event.getInventory());
        if (match == null) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) {
            return;
        }
        int quality = CatalogItemMatch.qualityOf(match.base());
        long seed = ThreadLocalRandom.current().nextLong();
        ItemStack stamped = itemFactory.create(match.resultTemplate(), seed, quality);
        CatalogIdentity.ensure(stamped, itemCatalog);
        event.setCurrentItem(stamped.clone());
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player player && !event.isShiftClick()) {
            player.setItemOnCursor(stamped.clone());
        }
    }

    private Match match(SmithingInventory inventory) {
        ItemStack template = inventory.getInputTemplate();
        ItemStack base = inventory.getInputEquipment();
        ItemStack addition = inventory.getInputMineral();
        if (base == null || base.getType().isAir()) {
            return null;
        }
        if (!isNetheriteTemplate(template) || !isNetheriteIngot(addition)) {
            return null;
        }

        for (ItemTemplate resultTemplate : itemCatalog.all().values()) {
            for (RecipeSpec recipe : resultTemplate.recipes()) {
                if (!recipe.isNetherite() || !recipe.shouldRegister()) {
                    continue;
                }
                Optional<ItemTemplate> source = itemCatalog.template(recipe.sourceItem());
                if (source.isEmpty() || !CatalogItemMatch.matchesTemplate(base, source.get())) {
                    continue;
                }
                return new Match(resultTemplate, base);
            }
        }
        return null;
    }

    private static boolean isNetheriteTemplate(ItemStack stack) {
        return stack != null && stack.getType() == Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE;
    }

    private static boolean isNetheriteIngot(ItemStack stack) {
        return stack != null && stack.getType() == Material.NETHERITE_INGOT;
    }

    private record Match(ItemTemplate resultTemplate, ItemStack base) {
    }
}
