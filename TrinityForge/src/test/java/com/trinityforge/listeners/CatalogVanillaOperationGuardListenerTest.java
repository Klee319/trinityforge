package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.RecipeSpec;
import io.papermc.paper.event.block.CompostItemEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Map;
import java.util.Optional;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class CatalogVanillaOperationGuardListenerTest {

    private ItemCatalogConfig catalog;
    private CatalogVanillaOperationGuardListener listener;
    private ItemTemplate halo;
    private Map<String, ItemTemplate> templates;
    private CraftingFeaturesConfig features;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        halo = new ItemTemplate(
                "novus_criculus_luminis", Material.GLOWSTONE, "新生の光輪", 84,
                BindType.TRADEABLE, 0, null);
        catalog = mock(ItemCatalogConfig.class);
        templates = new HashMap<>();
        templates.put(halo.id(), halo);
        when(catalog.all()).thenReturn(templates);
        when(catalog.template(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(templates.get(invocation.getArgument(0, String.class))));
        features = mock(CraftingFeaturesConfig.class);
        when(features.brewUnlocks()).thenReturn(Map.of());
        listener = new CatalogVanillaOperationGuardListener(catalog, features);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void blocksPlacementOfTheReportedHalo() {
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getItemInHand()).thenReturn(catalogStack(halo));

        listener.onBlockPlace(event);

        verify(event).setCancelled(true);
    }

    @Test
    void blocksCookingAndFuelConsumptionOfCatalogItems() {
        ItemStack protectedItem = catalogStack(halo);
        BlockCookEvent cook = mock(BlockCookEvent.class);
        when(cook.getSource()).thenReturn(protectedItem);
        FurnaceBurnEvent furnaceFuel = mock(FurnaceBurnEvent.class);
        when(furnaceFuel.getFuel()).thenReturn(protectedItem);
        BrewingStandFuelEvent brewingFuel = mock(BrewingStandFuelEvent.class);
        when(brewingFuel.getFuel()).thenReturn(protectedItem);

        listener.onBlockCook(cook);
        listener.onFurnaceBurn(furnaceFuel);
        listener.onBrewingFuel(brewingFuel);

        verify(cook).setCancelled(true);
        verify(furnaceFuel).setCancelled(true);
        verify(brewingFuel).setCancelled(true);
    }

    @Test
    void customBrewIngredientCannotTransformAnUndeclaredBottleBaseInTheSameStand() {
        CraftingFeaturesConfig.BrewPotionSpec spec = new CraftingFeaturesConfig.BrewPotionSpec(
                "WATER", "custom:" + halo.id(), mock(PotionEffectType.class), 200, 0);
        when(features.brewUnlocks()).thenReturn(Map.of(
                "halo_brew", new CraftingFeaturesConfig.BrewUnlockGroup(java.util.List.of(spec))));

        org.bukkit.inventory.BrewerInventory inventory = mock(org.bukkit.inventory.BrewerInventory.class);
        when(inventory.getIngredient()).thenReturn(catalogStack(halo));
        ItemStack water = potion(PotionType.WATER);
        ItemStack awkward = potion(PotionType.AWKWARD);
        when(inventory.getItem(0)).thenReturn(water);
        when(inventory.getItem(1)).thenReturn(awkward);
        org.bukkit.event.inventory.BrewEvent event = mock(org.bukkit.event.inventory.BrewEvent.class);
        when(event.getContents()).thenReturn(inventory);

        listener.onBrew(event);

        verify(event).setCancelled(true);
    }

    @Test
    void blocksDirectAndHopperCompostingOfCatalogItems() {
        CompostItemEvent event = mock(CompostItemEvent.class);
        when(event.getItem()).thenReturn(catalogStack(halo));
        Inventory source = mock(Inventory.class);
        Inventory composter = mock(Inventory.class);
        when(source.getType()).thenReturn(InventoryType.HOPPER);
        when(composter.getType()).thenReturn(InventoryType.COMPOSTER);
        InventoryMoveItemEvent move = mock(InventoryMoveItemEvent.class);
        when(move.getItem()).thenReturn(catalogStack(halo));
        when(move.getSource()).thenReturn(source);
        when(move.getDestination()).thenReturn(composter);

        listener.onCompost(event);
        listener.onComposterHopperMove(move);

        verify(event).setWillRaiseLevel(false);
        verify(move).setCancelled(true);
    }

    @Test
    void clearsDefaultAnvilSmithingAndGrindstoneResults() {
        ItemStack protectedItem = catalogStack(halo);

        AnvilInventory anvil = mock(AnvilInventory.class);
        when(anvil.getContents()).thenReturn(new ItemStack[] {protectedItem, null, new ItemStack(Material.GLOWSTONE)});
        when(anvil.getFirstItem()).thenReturn(protectedItem);
        PrepareAnvilEvent anvilEvent = mock(PrepareAnvilEvent.class);
        when(anvilEvent.getInventory()).thenReturn(anvil);

        SmithingInventory smithing = mock(SmithingInventory.class);
        when(smithing.getContents()).thenReturn(new ItemStack[] {null, protectedItem, null, null});
        PrepareSmithingEvent smithingEvent = mock(PrepareSmithingEvent.class);
        when(smithingEvent.getInventory()).thenReturn(smithing);

        GrindstoneInventory grindstone = mock(GrindstoneInventory.class);
        when(grindstone.getContents()).thenReturn(new ItemStack[] {protectedItem, null, protectedItem});
        PrepareGrindstoneEvent grindstoneEvent = mock(PrepareGrindstoneEvent.class);
        when(grindstoneEvent.getInventory()).thenReturn(grindstone);

        listener.onPrepareAnvil(anvilEvent);
        listener.onPrepareSmithing(smithingEvent);
        listener.onPrepareGrindstone(grindstoneEvent);

        verify(anvilEvent).setResult(null);
        verify(smithingEvent).setResult(null);
        verify(grindstoneEvent).setResult(null);
    }

    @Test
    void keepsExplicitCatalogCombineAndNetheriteRecipesAvailable() {
        ItemTemplate diamond = template("diamond_guard", Material.DIAMOND_SWORD, 101);
        ItemTemplate catalyst = template("guard_catalyst", Material.AMETHYST_SHARD, 102);
        ItemTemplate combined = new ItemTemplate(
                "combined_guard", Material.NETHERITE_SWORD, "Combined", 103,
                BindType.TRADEABLE, 0, null, java.util.List.of(),
                java.util.List.of(RecipeSpec.combine(diamond.id(), catalyst.id(), 10, true, 1)),
                null, false);
        ItemTemplate upgraded = new ItemTemplate(
                "netherite_guard", Material.NETHERITE_SWORD, "Upgraded", 104,
                BindType.TRADEABLE, 0, null, java.util.List.of(),
                java.util.List.of(RecipeSpec.netherite(diamond.id(), 1)), null, false);
        templates.put(diamond.id(), diamond);
        templates.put(catalyst.id(), catalyst);
        templates.put(combined.id(), combined);
        templates.put(upgraded.id(), upgraded);

        ItemStack diamondStack = catalogStack(diamond);
        ItemStack catalystStack = catalogStack(catalyst);
        AnvilInventory anvil = mock(AnvilInventory.class);
        when(anvil.getContents()).thenReturn(new ItemStack[] {diamondStack, catalystStack, null});
        when(anvil.getFirstItem()).thenReturn(diamondStack);
        when(anvil.getSecondItem()).thenReturn(catalystStack);
        PrepareAnvilEvent anvilEvent = mock(PrepareAnvilEvent.class);
        when(anvilEvent.getInventory()).thenReturn(anvil);

        SmithingInventory smithing = mock(SmithingInventory.class);
        when(smithing.getContents()).thenReturn(new ItemStack[] {
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                diamondStack, new ItemStack(Material.NETHERITE_INGOT), null});
        when(smithing.getInputTemplate()).thenReturn(
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        when(smithing.getInputEquipment()).thenReturn(diamondStack);
        when(smithing.getInputMineral()).thenReturn(new ItemStack(Material.NETHERITE_INGOT));
        PrepareSmithingEvent smithingEvent = mock(PrepareSmithingEvent.class);
        when(smithingEvent.getInventory()).thenReturn(smithing);

        listener.onPrepareAnvil(anvilEvent);
        listener.onPrepareSmithing(smithingEvent);

        verify(anvilEvent, org.mockito.Mockito.never()).setResult(null);
        verify(smithingEvent, org.mockito.Mockito.never()).setResult(null);
    }

    @Test
    void blocksGlowstoneChargingARespawnAnchor() {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        Block anchor = mock(Block.class);
        when(anchor.getType()).thenReturn(Material.RESPAWN_ANCHOR);
        when(event.getClickedBlock()).thenReturn(anchor);
        when(event.getItem()).thenReturn(catalogStack(halo));

        listener.onConsumptiveBlockUse(event);

        verify(event).setCancelled(true);
    }

    @Test
    void blocksEyeOfEnderLaunchAndAmethystAllayDuplication() {
        ItemTemplate eye = template("someones_eyes", Material.ENDER_EYE, 85);
        ItemTemplate lock = template("skill_node_lock", Material.AMETHYST_SHARD, 3);
        templates.put(eye.id(), eye);
        templates.put(lock.id(), lock);

        PlayerInteractEvent launch = mock(PlayerInteractEvent.class);
        when(launch.getItem()).thenReturn(catalogStack(eye));

        PlayerInteractEntityEvent allayUse = mock(PlayerInteractEntityEvent.class);
        Entity allay = mock(Entity.class);
        when(allay.getType()).thenReturn(EntityType.ALLAY);
        when(allayUse.getRightClicked()).thenReturn(allay);
        when(allayUse.getHand()).thenReturn(EquipmentSlot.HAND);
        Player player = mock(Player.class);
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(playerInventory);
        when(playerInventory.getItemInMainHand()).thenReturn(catalogStack(lock));
        when(allayUse.getPlayer()).thenReturn(player);

        listener.onConsumptiveBlockUse(launch);
        listener.onConsumptiveEntityUse(allayUse);

        verify(launch).setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        verify(launch, org.mockito.Mockito.never()).setCancelled(true);
        verify(allayUse).setCancelled(true);
    }

    @Test
    void eyeOfEnderItemUseIsDeniedWithoutCancellingChestInteraction() {
        ItemTemplate eye = template("someones_eyes", Material.ENDER_EYE, 85);
        templates.put(eye.id(), eye);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        Block chest = mock(Block.class);
        when(chest.getType()).thenReturn(Material.CHEST);
        when(event.getClickedBlock()).thenReturn(chest);
        when(event.getItem()).thenReturn(catalogStack(eye));

        listener.onConsumptiveBlockUse(event);

        verify(event).setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void staleStonecutterResultCannotBeTakenAfterCatalogInputSwap() {
        Inventory stonecutter = mock(Inventory.class);
        when(stonecutter.getType()).thenReturn(InventoryType.STONECUTTER);
        when(stonecutter.getSize()).thenReturn(2);
        when(stonecutter.getContents()).thenReturn(new ItemStack[] {catalogStack(halo), new ItemStack(Material.STONE)});
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(stonecutter);
        when(event.getRawSlot()).thenReturn(1);

        listener.onVanillaWorkstationResultTake(event);

        verify(event).setCancelled(true);
    }

    @Test
    void cmdLessStackIsNotBlocked() {
        ItemStack stale = new ItemStack(Material.GLOWSTONE);
        ItemMeta meta = stale.getItemMeta();
        ItemData.of(meta).setCatalogId(halo.id());
        stale.setItemMeta(meta);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getItemInHand()).thenReturn(stale);

        listener.onBlockPlace(event);

        verify(event, org.mockito.Mockito.never()).setCancelled(true);
        assertFalse(event.isCancelled());
    }

    private static ItemStack catalogStack(ItemTemplate template) {
        ItemStack stack = new ItemStack(template.material());
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(template.customModelData());
        ItemData.of(meta).setCatalogId(template.id());
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemTemplate template(String id, Material material, int cmd) {
        return new ItemTemplate(id, material, id, cmd, BindType.TRADEABLE, 0, null);
    }

    private static ItemStack potion(PotionType base) {
        ItemStack bottle = mock(ItemStack.class);
        PotionMeta meta = mock(PotionMeta.class);
        when(bottle.getType()).thenReturn(Material.POTION);
        when(bottle.getItemMeta()).thenReturn(meta);
        when(meta.getBasePotionType()).thenReturn(base);
        return bottle;
    }
}
