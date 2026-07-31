package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BrewUnlockListener} の<b>投入ゲート</b> (2026-07-31 D10 = K-13、オーケストレータ決定
 * 「未解放プレイヤーが素材を置いたときは投入自体を弾く」)。
 *
 * <p>{@code PotionMix} を登録すると素材が誰でも上段に置けて醸造が始まるため、完成時に
 * {@code BrewEvent} をキャンセルするだけだと <b>20秒ごとにブレイズパウダーを1個燃やし続ける</b>
 * (バニラは {@code brewable && fuel>0} で無条件に再開し、{@code BrewingStartEvent} は
 * {@code Cancellable} ではない)。投入自体を弾くのがその対策。
 */
class BrewUnlockIngredientGateTest {

    private static final NamespacedKey ARS_ID = new NamespacedKey("arspaper", "custom_item_id");

    // ---- 優先度の不変条件 ----

    @Test
    void brewHandlerOrderKeepsQualityOnCustomPotionsAndCannotDuplicateIngredients() throws Exception {
        EventPriority unlock = priorityOf(BrewUnlockListener.class);
        EventPriority quality = priorityOf(PotionQualityListener.class);
        EventPriority guard = priorityOf(CatalogVanillaOperationGuardListener.class);
        EventPriority save = priorityOf(BrewIngredientSaveListener.class);

        assertEquals(EventPriority.NORMAL, unlock,
                "結果の差し替えは品質付与(PotionQualityListener=HIGH)より前でなければ、"
                        + "results.set で丸ごと差し替えたときに品質が消える");
        assertEquals(EventPriority.HIGH, quality);
        assertEquals(EventPriority.HIGH, guard,
                "キャンセラは +1 ミラー(BrewIngredientSaveListener=HIGHEST)より前でなければ"
                        + "「+1 されたのに shrink されない」= 素材の純増になる");
        assertEquals(EventPriority.HIGHEST, save);
        assertTrue(unlock.ordinal() < quality.ordinal() && quality.ordinal() < save.ordinal(),
                "NORMAL < HIGH < HIGHEST の順序自体が不変条件 (過去にこの順序で複製バグが出ている)");
    }

    private static EventPriority priorityOf(Class<?> listener) throws Exception {
        EventHandler annotation = listener.getMethod("onBrew", BrewEvent.class)
                .getAnnotation(EventHandler.class);
        return annotation.priority();
    }

    // ---- クリックによる投入 ----

    @Test
    void lockedPlayerCannotPlaceACustomIngredientIntoTheStand() {
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        ItemStack tusk = arsItem(Material.BONE, "hoglin_tusk");
        InventoryClickEvent event = placeEvent(player, inv, tusk);

        listener(false).onBrewerClick(event);

        verify(event).setCancelled(true);
    }

    @Test
    void unlockedPlayerCanStillPlaceTheSameCustomIngredient() {
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        ItemStack tusk = arsItem(Material.BONE, "hoglin_tusk");
        InventoryClickEvent event = placeEvent(player, inv, tusk);

        listener(true).onBrewerClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void aPlainVanillaBrewIngredientIsNotBlockedWhenNoMatchingBottleIsLoaded() {
        // THICK + SUGAR は brew-unlocks のゲート対象だが、AWKWARD ビンしか入っていない醸造台では
        // バニラの俊敏のポーションを作る正当な操作。未解放でも弾いてはいけない。
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.AWKWARD);
        InventoryClickEvent event = placeEvent(player, inv, plain(Material.SUGAR));

        listener(false, new BrewPotionSpec("THICK", "SUGAR", mock(PotionEffectType.class), 3600, 1))
                .onBrewerClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void aPlainVanillaBrewIngredientIsBlockedOnceTheGatedBaseIsLoaded() {
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        InventoryClickEvent event = placeEvent(player, inv, plain(Material.SUGAR));

        listener(false, new BrewPotionSpec("THICK", "SUGAR", mock(PotionEffectType.class), 3600, 1))
                .onBrewerClick(event);

        verify(event).setCancelled(true);
    }

    @Test
    void anUnrelatedItemIsNeverBlocked() {
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        InventoryClickEvent event = placeEvent(player, inv, plain(Material.DIAMOND));

        listener(false).onBrewerClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void shiftClickingFromThePlayerInventoryIsAlsoBlocked() {
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        ItemStack tusk = arsItem(Material.BONE, "hoglin_tusk");
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(inv);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getAction()).thenReturn(InventoryAction.MOVE_TO_OTHER_INVENTORY);
        when(event.getClickedInventory()).thenReturn(mock(org.bukkit.inventory.PlayerInventory.class));
        when(event.getCurrentItem()).thenReturn(tusk);

        listener(false).onBrewerClick(event);

        verify(event).setCancelled(true);
    }

    // ---- ホッパー経由 ----

    @Test
    void hopperInsertionIsBlockedWhenNoNearbyPlayerHoldsTheUnlock() {
        BrewerInventory inv = standWith(PotionType.THICK);
        ItemStack tusk = arsItem(Material.BONE, "hoglin_tusk");
        InventoryMoveItemEvent event = mock(InventoryMoveItemEvent.class);
        when(event.getDestination()).thenReturn(inv);
        when(event.getItem()).thenReturn(tusk);

        listener(false).onHopperMove(event);

        verify(event).setCancelled(true);
    }

    @Test
    void hopperInsertionIsAllowedWhenAnUnlockedPlayerIsViewingTheStand() {
        BrewerInventory inv = standWith(PotionType.THICK);
        ItemStack tusk = arsItem(Material.BONE, "hoglin_tusk");
        InventoryMoveItemEvent event = mock(InventoryMoveItemEvent.class);
        when(event.getDestination()).thenReturn(inv);
        when(event.getItem()).thenReturn(tusk);

        listener(true).onHopperMove(event);

        verify(event, never()).setCancelled(true);
    }

    // ---- ヘルパー ----

    private static BrewUnlockListener listener(boolean unlocked, BrewPotionSpec... extraSpecs) {
        CraftingFeaturesConfig features = mock(CraftingFeaturesConfig.class);
        BrewPotionSpec apex = new BrewPotionSpec(
                "THICK", "custom:hoglin_tusk", mock(PotionEffectType.class), 3600, 2);
        java.util.List<BrewPotionSpec> specs = new java.util.ArrayList<>();
        specs.add(apex);
        specs.addAll(List.of(extraSpecs));
        when(features.brewUnlocks()).thenReturn(
                Map.of("apex-brew", new BrewUnlockGroup(List.copyOf(specs))));

        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.isActive(org.mockito.ArgumentMatchers.any(Player.class),
                eq("brew:apex-brew"))).thenReturn(unlocked);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("BrewUnlockIngredientGateTest"));
        return new BrewUnlockListener(dedicatedEffects, features, plugin);
    }

    private static InventoryClickEvent placeEvent(Player player, BrewerInventory inv, ItemStack moving) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(inv);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getAction()).thenReturn(InventoryAction.PLACE_ALL);
        when(event.getClickedInventory()).thenReturn(inv);
        when(event.getCursor()).thenReturn(moving);
        return event;
    }

    /** 対象の醸造台: 指定ベースのビンが slot0 に入っている。閲覧者は1人。 */
    private static BrewerInventory standWith(PotionType base) {
        BrewerInventory inv = mock(BrewerInventory.class);
        ItemStack bottle = mock(ItemStack.class);
        PotionMeta meta = mock(PotionMeta.class);
        when(bottle.getType()).thenReturn(Material.POTION);
        when(bottle.getItemMeta()).thenReturn(meta);
        when(meta.getBasePotionType()).thenReturn(base);
        when(inv.getItem(0)).thenReturn(bottle);
        when(inv.getViewers()).thenReturn(List.of(mock(Player.class)));
        return inv;
    }

    private static ItemStack plain(Material type) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(type);
        when(stack.hasItemMeta()).thenReturn(false);
        return stack;
    }

    private static ItemStack arsItem(Material type, String id) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(stack.getType()).thenReturn(type);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(ARS_ID, PersistentDataType.STRING)).thenReturn(id);
        return stack;
    }
}
