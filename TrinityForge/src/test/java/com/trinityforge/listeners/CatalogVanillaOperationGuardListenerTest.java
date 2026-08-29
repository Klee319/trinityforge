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
import org.bukkit.event.block.Action;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class CatalogVanillaOperationGuardListenerTest {

    private ItemCatalogConfig catalog;
    private com.trinityforge.config.domains.DedicatedEffectsConfig dedicatedEffects;
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
        dedicatedEffects = mock(com.trinityforge.config.domains.DedicatedEffectsConfig.class);
        // パーティクルシードの除外(2026-08-25 / W-214)はこのテストの対象外なので、
        // シードが1つも定義されていない = 常に「シード付与ではない」状態にしておく。
        com.trinityforge.config.domains.SpecialRewardsConfig specialRewards =
                mock(com.trinityforge.config.domains.SpecialRewardsConfig.class);
        when(specialRewards.particleSeeds()).thenReturn(Map.of());
        listener = new CatalogVanillaOperationGuardListener(catalog, features, dedicatedEffects,
                specialRewards, mock(com.trinityforge.progression.SpecialRewardService.class));
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
    void clearsDefaultAnvilAndSmithingResults() {
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

        listener.onPrepareAnvil(anvilEvent);
        listener.onPrepareSmithing(smithingEvent);

        verify(anvilEvent).setResult(null);
        verify(smithingEvent).setResult(null);
    }

    @Test
    void blockedPrepareSendsActionBarToThePlayer() {
        ItemStack protectedItem = catalogStack(halo);
        Player player = mock(Player.class);
        org.bukkit.inventory.view.AnvilView view = mock(org.bukkit.inventory.view.AnvilView.class);
        when(view.getPlayer()).thenReturn(player);

        AnvilInventory anvil = mock(AnvilInventory.class);
        when(anvil.getContents()).thenReturn(new ItemStack[] {protectedItem, null, null});
        when(anvil.getFirstItem()).thenReturn(protectedItem);
        PrepareAnvilEvent anvilEvent = mock(PrepareAnvilEvent.class);
        when(anvilEvent.getInventory()).thenReturn(anvil);
        when(anvilEvent.getView()).thenReturn(view);

        listener.onPrepareAnvil(anvilEvent);

        verify(anvilEvent).setResult(null);
        verify(player).sendActionBar(org.mockito.ArgumentMatchers.any(net.kyori.adventure.text.Component.class));
    }

    /**
     * U4: 砥石で拒否するのは「カタログ品を素材として食う修理マージ」だけ。
     * 片方だけがカタログ品だと、素材側のロール/品質/バインドが黙って消える。
     */
    @Test
    void clearsGrindstoneResultOnlyWhenTheMergeWouldConsumeACatalogIdentity() {
        PrepareGrindstoneEvent event = grindstoneEvent(catalogStack(halo), new ItemStack(Material.GLOWSTONE));

        listener.onPrepareGrindstone(event);

        verify(event).setResult(null);
    }

    /** U4: 片側だけの投入 = 純粋なエンチャント除去なので結果枠を消してはいけない。 */
    @Test
    void keepsGrindstoneResultForPureEnchantRemoval() {
        PrepareGrindstoneEvent upperOnly = grindstoneEvent(catalogStack(halo), null);
        PrepareGrindstoneEvent lowerOnly = grindstoneEvent(null, catalogStack(halo));

        listener.onPrepareGrindstone(upperOnly);
        listener.onPrepareGrindstone(lowerOnly);

        verify(upperOnly, org.mockito.Mockito.never()).setResult(null);
        verify(lowerOnly, org.mockito.Mockito.never()).setResult(null);
    }

    /** U4: 両側が同一 catalogId なら identity は消えない(同種修理)ので許可する。 */
    @Test
    void keepsGrindstoneResultWhenBothSlotsShareTheSameCatalogIdentity() {
        PrepareGrindstoneEvent event = grindstoneEvent(catalogStack(halo), catalogStack(halo));

        listener.onPrepareGrindstone(event);

        verify(event, org.mockito.Mockito.never()).setResult(null);
    }

    /** U4: エンチャント本の適用と同一 identity の修理は金床でも許可する。 */
    @Test
    void keepsAnvilResultForEnchantedBookApplicationAndSameIdentityRepair() {
        ItemTemplate blade = template("guard_blade", Material.DIAMOND_SWORD, 111);
        templates.put(blade.id(), blade);
        ItemStack sword = catalogStack(blade);
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);

        AnvilInventory bookAnvil = mock(AnvilInventory.class);
        when(bookAnvil.getContents()).thenReturn(new ItemStack[] {sword, book, null});
        when(bookAnvil.getFirstItem()).thenReturn(sword);
        when(bookAnvil.getSecondItem()).thenReturn(book);
        PrepareAnvilEvent bookEvent = mock(PrepareAnvilEvent.class);
        when(bookEvent.getInventory()).thenReturn(bookAnvil);

        AnvilInventory repairAnvil = mock(AnvilInventory.class);
        when(repairAnvil.getContents()).thenReturn(new ItemStack[] {sword, sword.clone(), null});
        when(repairAnvil.getFirstItem()).thenReturn(sword);
        when(repairAnvil.getSecondItem()).thenReturn(sword.clone());
        PrepareAnvilEvent repairEvent = mock(PrepareAnvilEvent.class);
        when(repairEvent.getInventory()).thenReturn(repairAnvil);

        listener.onPrepareAnvil(bookEvent);
        listener.onPrepareAnvil(repairEvent);

        verify(bookEvent, org.mockito.Mockito.never()).setResult(null);
        verify(repairEvent, org.mockito.Mockito.never()).setResult(null);
    }

    private static PrepareGrindstoneEvent grindstoneEvent(ItemStack upper, ItemStack lower) {
        GrindstoneInventory grindstone = mock(GrindstoneInventory.class);
        when(grindstone.getItem(0)).thenReturn(upper);
        when(grindstone.getItem(1)).thenReturn(lower);
        PrepareGrindstoneEvent event = mock(PrepareGrindstoneEvent.class);
        when(event.getInventory()).thenReturn(grindstone);
        return event;
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
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
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
        when(launch.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
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
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        Block chest = mock(Block.class);
        when(chest.getType()).thenReturn(Material.CHEST);
        when(event.getClickedBlock()).thenReturn(chest);
        when(event.getItem()).thenReturn(catalogStack(eye));

        listener.onConsumptiveBlockUse(event);

        verify(event).setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }

    /**
     * 2026-08-03 回帰: カタログ製エンダーアイを<b>虚空(空中)</b>へ右クリックしたときもガードが走ること。
     *
     * <p>{@code RIGHT_CLICK_AIR} の {@link PlayerInteractEvent} は
     * 「clickedBlock が null → useClickedBlock = DENY」でコンストラクタが初期化するため
     * <b>生成直後から {@code isCancelled()} が true</b>。ハンドラに {@code ignoreCancelled = true} を
     * 付けていると Bukkit のイベントバスが一切配送せず、バニラの {@code EyeOfEnder} が飛んで
     * カタログ品(PDC/CMD)が素のエンダーアイに戻る＝カスタムアイテムの消滅になっていた。
     */
    @Test
    void enderEyeThrownAtTheVoidIsStillGuarded() throws Exception {
        ItemTemplate eye = template("someones_eyes", Material.ENDER_EYE, 85);
        templates.put(eye.id(), eye);
        PlayerInteractEvent event = new PlayerInteractEvent(
                mock(Player.class), Action.RIGHT_CLICK_AIR, catalogStack(eye), null,
                org.bukkit.block.BlockFace.SELF, EquipmentSlot.HAND);

        assertTrue(event.isCancelled(),
                "前提: RIGHT_CLICK_AIR は生成直後から isCancelled()==true(この罠そのもの)");
        assertFalse(CatalogVanillaOperationGuardListener.class
                        .getMethod("onConsumptiveBlockUse", PlayerInteractEvent.class)
                        .getAnnotation(org.bukkit.event.EventHandler.class).ignoreCancelled(),
                "ignoreCancelled=true だと虚空右クリックには一度も配送されない");

        listener.onConsumptiveBlockUse(event);

        assertEquals(org.bukkit.event.Event.Result.DENY, event.useItemInHand(),
                "カタログ製エンダーアイの投擲を止めないと素のエンダーアイに戻って消滅する");
    }

    /** 他プラグインが本当にキャンセルした場合({@code useItemInHand == DENY})は従来どおり尊重する。 */
    @Test
    void alreadyDeniedItemUseIsLeftAlone() {
        ItemTemplate eye = template("someones_eyes", Material.ENDER_EYE, 85);
        templates.put(eye.id(), eye);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DENY);

        listener.onConsumptiveBlockUse(event);

        verify(event, org.mockito.Mockito.never())
                .setUseItemInHand(org.bukkit.event.Event.Result.DENY);
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
