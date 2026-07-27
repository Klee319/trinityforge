package com.trinityforge.listeners;

import com.trinityforge.integration.ars.ArsProgressionBridge;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.PreviewRollSeeds;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Catalog {@code recipe.method: combine} on the anvil (金床).
 *
 * <p>Left (first) = {@code source-item} (合成元), right (second) = {@code addition-item} (合成対象),
 * result = the catalog entry that owns the recipe (合成先). Quality inherits the source when
 * {@code inherit-source-quality}, otherwise the average of both inputs.
 */
public final class CatalogAnvilListener implements Listener {

    /** Flat anvil XP level cost for a successful TF combine preview. */
    private static final int COMBINE_REPAIR_COST = 1;

    private final Plugin plugin;
    private final ItemCatalogConfig itemCatalog;
    private final ItemFactory itemFactory;

    public CatalogAnvilListener(Plugin plugin, ItemCatalogConfig itemCatalog, ItemFactory itemFactory) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepare(PrepareAnvilEvent event) {
        AnvilInventory inventory = event.getInventory();
        Match match = match(inventory);
        if (match == null) {
            return;
        }
        int quality = resolveQuality(match);
        ItemStack result = itemFactory.create(match.resultTemplate(), PreviewRollSeeds.ANVIL, quality);
        CatalogIdentity.ensure(result, itemCatalog);
        event.setResult(result);
        if (event.getView() instanceof AnvilView anvilView) {
            anvilView.setRepairCost(COMBINE_REPAIR_COST);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTakeResult(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory inventory)) {
            return;
        }
        if (event.getRawSlot() != 2) {
            return;
        }
        Match match = match(inventory);
        if (match == null) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int quality = resolveQuality(match);
        long seed = ThreadLocalRandom.current().nextLong();
        ItemStack stamped = itemFactory.create(match.resultTemplate(), seed, quality);
        CatalogIdentity.ensure(stamped, itemCatalog);
        event.setCurrentItem(stamped.clone());
        if (!event.isShiftClick()) {
            player.setItemOnCursor(stamped.clone());
        }
        plugin.getServer().getScheduler().runTask(plugin,
                () -> restampAnvilPreviews(player, match.resultTemplate(), quality));

        if (match.recipe().combineExp() > 0) {
            ArsProgressionBridge.grantSmithingExp(plugin, player, match.recipe().combineExp());
        }
    }

    private void restampAnvilPreviews(Player player, ItemTemplate resultTemplate, int quality) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (restampIfAnvilPreview(stack, resultTemplate, quality)) {
                inv.setItem(i, stack);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (restampIfAnvilPreview(cursor, resultTemplate, quality)) {
            player.setItemOnCursor(cursor);
        }
    }

    private boolean restampIfAnvilPreview(ItemStack stack, ItemTemplate resultTemplate, int quality) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        if (!CatalogItemMatch.matchesTemplate(stack, resultTemplate)) {
            return false;
        }
        var data = com.trinityforge.pdc.ItemData.of(stack.getItemMeta());
        var seed = data.rollSeed();
        if (seed.isEmpty() || seed.get() != PreviewRollSeeds.ANVIL) {
            return false;
        }
        ItemStack stamped = itemFactory.create(resultTemplate, ThreadLocalRandom.current().nextLong(), quality);
        CatalogIdentity.ensure(stamped, itemCatalog);
        stack.setItemMeta(stamped.getItemMeta());
        return true;
    }

    private Match match(AnvilInventory inventory) {
        ItemStack first = inventory.getFirstItem();
        ItemStack second = inventory.getSecondItem();
        if (first == null || first.getType().isAir() || second == null || second.getType().isAir()) {
            return null;
        }

        for (ItemTemplate resultTemplate : itemCatalog.all().values()) {
            for (RecipeSpec recipe : resultTemplate.recipes()) {
                if (!recipe.isCombine() || !recipe.shouldRegister()) {
                    continue;
                }
                Optional<ItemTemplate> source = itemCatalog.template(recipe.sourceItem());
                if (source.isEmpty() || !CatalogItemMatch.matchesTemplate(first, source.get())) {
                    continue;
                }
                Optional<ItemTemplate> addition = itemCatalog.template(recipe.additionItem());
                if (addition.isEmpty() || !CatalogItemMatch.matchesTemplate(second, addition.get())) {
                    continue;
                }
                return new Match(resultTemplate, recipe, first, second);
            }
        }
        return null;
    }

    private static int resolveQuality(Match match) {
        int sourceQ = CatalogItemMatch.qualityOf(match.first());
        if (match.recipe().inheritSourceQuality()) {
            return sourceQ;
        }
        int additionQ = CatalogItemMatch.qualityOf(match.second());
        return (sourceQ + additionQ) / 2;
    }

    private record Match(ItemTemplate resultTemplate, RecipeSpec recipe, ItemStack first, ItemStack second) {
    }
}
