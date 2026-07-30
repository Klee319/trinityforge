package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.CatalogVanillaOperationPolicy;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.RecipeSpec;
import io.papermc.paper.event.block.CompostItemEvent;
import io.papermc.paper.event.entity.EntityCompostItemEvent;
import io.papermc.paper.event.player.CartographyItemEvent;
import io.papermc.paper.event.player.PlayerLoomPatternSelectEvent;
import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Prevents catalog items from falling back to their base Material's consuming/transforming vanilla
 * behaviour. Purpose-built TF operations remain available: catalog workbench recipes are handled by
 * {@link CatalogWorkbenchListener}, and declared combine/netherite recipes are allowed here for
 * {@link CatalogAnvilListener}/{@link CatalogSmithingListener} to produce their catalog result.
 */
public final class CatalogVanillaOperationGuardListener implements Listener {

    private final ItemCatalogConfig catalog;
    private final CraftingFeaturesConfig features;

    public CatalogVanillaOperationGuardListener(ItemCatalogConfig catalog, CraftingFeaturesConfig features) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.features = Objects.requireNonNull(features, "features");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isCatalog(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockCook(BlockCookEvent event) {
        if (isCatalog(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        if (isCatalog(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrewingFuel(BrewingStandFuelEvent event) {
        if (isCatalog(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        BrewerInventory inventory = event.getContents();
        ItemStack ingredient = inventory.getIngredient();
        if (isCatalog(ingredient) && !isDeclaredCustomBrewIngredient(ingredient, inventory)) {
            event.setCancelled(true);
            return;
        }
        for (int slot = 0; slot < 3; slot++) {
            if (isCatalog(inventory.getItem(slot))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCompost(CompostItemEvent event) {
        if (isCatalog(event.getItem())) {
            event.setWillRaiseLevel(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityCompost(EntityCompostItemEvent event) {
        if (isCatalog(event.getItem())) {
            event.setWillRaiseLevel(false);
            event.setCancelled(true);
        }
    }

    /** CompostItemEvent itself cannot cancel hopper transfer, so stop the inventory move. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onComposterHopperMove(InventoryMoveItemEvent event) {
        if (isCatalog(event.getItem())
                && (event.getSource().getType() == InventoryType.COMPOSTER
                || event.getDestination().getType() == InventoryType.COMPOSTER)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStonecutterSelect(PlayerStonecutterRecipeSelectEvent event) {
        if (isCatalog(event.getStonecutterInventory().getInputItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCartography(CartographyItemEvent event) {
        if (containsCatalogItem(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLoomSelect(PlayerLoomPatternSelectEvent event) {
        if (containsCatalogItem(event.getLoomInventory())) {
            event.setCancelled(true);
        }
    }

    /** Result-take defense for stale previews after an input stack was replaced. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaWorkstationResultTake(InventoryClickEvent event) {
        Inventory inventory = event.getInventory();
        InventoryType type = inventory.getType();
        if (type != InventoryType.STONECUTTER
                && type != InventoryType.CARTOGRAPHY
                && type != InventoryType.LOOM) {
            return;
        }
        int resultSlot = inventory.getSize() - 1;
        if (event.getRawSlot() == resultSlot && containsCatalogItem(inventory)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        if (containsCatalogItem(event.getInventory())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inventory = event.getInventory();
        if (containsCatalogItem(inventory) && !matchesDeclaredCombine(inventory)) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        SmithingInventory inventory = event.getInventory();
        if (containsCatalogItem(inventory) && !matchesDeclaredNetherite(inventory)) {
            event.setResult(null);
        }
    }

    /**
     * Placement events do not cover vanilla right-click consumption against an existing block.
     * The bundled catalog currently exposes exactly these two base-material behaviours.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsumptiveBlockUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!isCatalog(item)) {
            return;
        }
        Material itemType = item.getType();
        // Eye of Ender is consumed both by air launch and by filling an end portal frame.
        if (itemType == Material.ENDER_EYE) {
            event.setUseItemInHand(Event.Result.DENY);
            return;
        }
        if (event.getClickedBlock() == null) {
            return;
        }
        Material target = event.getClickedBlock().getType();
        boolean vanillaConsumption =
                itemType == Material.GLOWSTONE && target == Material.RESPAWN_ANCHOR
                || item.getItemMeta() instanceof LeatherArmorMeta && target == Material.WATER_CAULDRON;
        if (vanillaConsumption) {
            event.setCancelled(true);
        }
    }

    /** Dancing Allays consume an amethyst shard for vanilla duplication. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsumptiveEntityUse(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getType() != EntityType.ALLAY || event.getHand() == null) {
            return;
        }
        ItemStack item = event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand();
        if (item.getType() == Material.AMETHYST_SHARD && isCatalog(item)) {
            event.setCancelled(true);
        }
    }

    private boolean isCatalog(ItemStack item) {
        return CatalogVanillaOperationPolicy.isCatalogItem(item, catalog);
    }

    private boolean containsCatalogItem(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        for (ItemStack item : inventory.getContents()) {
            if (isCatalog(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDeclaredCombine(AnvilInventory inventory) {
        ItemStack first = inventory.getFirstItem();
        ItemStack second = inventory.getSecondItem();
        if (first == null || second == null) {
            return false;
        }
        for (ItemTemplate result : catalog.all().values()) {
            for (RecipeSpec recipe : result.recipes()) {
                if (!recipe.isCombine() || !recipe.shouldRegister()) {
                    continue;
                }
                Optional<ItemTemplate> source = catalog.template(recipe.sourceItem());
                Optional<ItemTemplate> addition = catalog.template(recipe.additionItem());
                if (source.isPresent() && addition.isPresent()
                        && CatalogItemMatch.matchesTemplate(first, source.get())
                        && CatalogItemMatch.matchesTemplate(second, addition.get())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesDeclaredNetherite(SmithingInventory inventory) {
        if (inventory.getInputTemplate() == null
                || inventory.getInputTemplate().getType() != Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE
                || inventory.getInputMineral() == null
                || inventory.getInputMineral().getType() != Material.NETHERITE_INGOT) {
            return false;
        }
        ItemStack base = inventory.getInputEquipment();
        for (ItemTemplate result : catalog.all().values()) {
            for (RecipeSpec recipe : result.recipes()) {
                if (!recipe.isNetherite() || !recipe.shouldRegister()) {
                    continue;
                }
                Optional<ItemTemplate> source = catalog.template(recipe.sourceItem());
                if (source.isPresent() && CatalogItemMatch.matchesTemplate(base, source.get())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isDeclaredCustomBrewIngredient(ItemStack ingredient, BrewerInventory inventory) {
        Optional<String> catalogId = CatalogVanillaOperationPolicy.catalogIdOf(ingredient, catalog);
        if (catalogId.isEmpty()) {
            return false;
        }
        String expected = ("custom:" + catalogId.get()).toLowerCase(Locale.ROOT);
        List<CraftingFeaturesConfig.BrewPotionSpec> matchingSpecs = features.brewUnlocks().values().stream()
                .flatMap(group -> group.potions().stream())
                .filter(spec -> spec.ingredient() != null
                        && expected.equals(spec.ingredient().trim().toLowerCase(Locale.ROOT)))
                .toList();
        if (matchingSpecs.isEmpty()) {
            return false;
        }
        boolean foundPotion = false;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = inventory.getItem(slot);
            if (bottle == null || bottle.getType().isAir()) {
                continue;
            }
            if (!(bottle.getItemMeta() instanceof PotionMeta meta)) {
                return false;
            }
            foundPotion = true;
            if (matchingSpecs.stream().noneMatch(spec -> baseMatches(meta, spec.base()))) {
                return false;
            }
        }
        return foundPotion;
    }

    private static boolean baseMatches(PotionMeta meta, String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return true;
        }
        try {
            return meta.getBasePotionType()
                    == PotionType.valueOf(baseName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
