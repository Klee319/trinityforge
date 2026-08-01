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
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
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
 *
 * <p>砥石/金床は「カタログ品を素材として食う操作」だけを拒否する(U4)。エンチャント除去・
 * エンチャント本の適用・同一 identity の修理は identity を消費しないので許可する
 * ({@link #onPrepareGrindstone} / {@link #onPrepareAnvil} の各 javadoc)。
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

    /**
     * ⚠️ 優先度は {@code HIGH} 固定(他のハンドラと違って HIGHEST ではない)。
     *
     * <p>{@code BrewIngredientSaveListener}(HIGHEST)は「素材を +1 しておけば直後にバニラが
     * {@code shrink(1)} して相殺される」方式で材料節約を実装しているので、
     * <b>そのハンドラより後にキャンセルすると +1 だけが残って素材が純増する</b>
     * (同ファイルのクラスjavadoc「優先度が HIGHEST でなければならない理由」参照)。
     * 同じ HIGHEST に置くと登録順で先に +1 が走り、その後ここがキャンセルして複製になっていた
     * (2026-07-31 D10 の調査で発覚。カタログ品を醸造素材/ビン枠へ入れると発火する)。
     * <b>キャンセラは必ず HIGHEST より前</b>に置く。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
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

    /**
     * 砥石(U4): 「カタログ identity が消費されてバニラ品に化ける組み合わせ」だけを拒否する。
     *
     * <p>許可するのは (a) 片側のスロットだけが埋まっている = 純粋なエンチャント除去、
     * (b) 両側が同一 catalogId = 同種修理。拒否するのは「片方だけがカタログ品の修理マージ」で、
     * これは素材側に入れたカタログ品のロール/品質/バインドが黙って消えるため。
     * {@link PrepareGrindstoneEvent} は Cancellable ではないので拒否は {@code setResult(null)} で行う。
     *
     * <p>許可した組み合わせで砥石が剥がす表示名/lore/attribute の復元は
     * {@link GrindstonePreserveListener}(MONITOR = この判定の後)が担当する。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        ItemStack upper = event.getInventory().getItem(0);
        ItemStack lower = event.getInventory().getItem(1);
        if (!isCatalog(upper) && !isCatalog(lower)) {
            return;
        }
        if (isEmptySlot(upper) || isEmptySlot(lower)) {
            return;
        }
        if (sameCatalogIdentity(upper, lower)) {
            return;
        }
        event.setResult(null);
    }

    /**
     * 金床(U4): 宣言済み combine レシピに加えて、エンチャント本の適用と同一 identity の修理を許可する。
     * それ以外(素材アイテムによる修理・改名のみ等、カタログ品を素材として食う操作)は従来どおり拒否。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inventory = event.getInventory();
        if (!containsCatalogItem(inventory)) {
            return;
        }
        if (matchesDeclaredCombine(inventory)
                || appliesEnchantmentBook(inventory)
                || sameCatalogIdentity(inventory.getFirstItem(), inventory.getSecondItem())) {
            return;
        }
        event.setResult(null);
    }

    /** 第2スロットがエンチャント本({@link EnchantmentStorageMeta})なら、その適用は許可する。 */
    private static boolean appliesEnchantmentBook(AnvilInventory inventory) {
        ItemStack first = inventory.getFirstItem();
        ItemStack second = inventory.getSecondItem();
        if (isEmptySlot(first) || isEmptySlot(second) || !second.hasItemMeta()) {
            return false;
        }
        return second.getItemMeta() instanceof EnchantmentStorageMeta;
    }

    /** 両スロットが同一 catalogId のカタログ品か(= identity を消費しない同種修理)。 */
    private boolean sameCatalogIdentity(ItemStack first, ItemStack second) {
        if (isEmptySlot(first) || isEmptySlot(second)) {
            return false;
        }
        Optional<String> firstId = CatalogVanillaOperationPolicy.catalogIdOf(first, catalog);
        return firstId.isPresent()
                && firstId.equals(CatalogVanillaOperationPolicy.catalogIdOf(second, catalog));
    }

    private static boolean isEmptySlot(ItemStack item) {
        return item == null || item.getType().isAir();
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
