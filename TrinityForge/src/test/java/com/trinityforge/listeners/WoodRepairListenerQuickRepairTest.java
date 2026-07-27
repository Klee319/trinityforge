package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WoodRepairListener#onInventoryClick}: 金床を使わないインベントリ内クイック修繕。
 * quick-repair素材をカーソルに、ダメージ済み装備をクリック対象にした場合に、対象の耐久が
 * durability分回復し、カーソルの素材が1個消費され、イベントがキャンセルされることを検証する。
 * quick-repair:false の素材、gate off(効果非保有)ではno-op(キャンセルされない)ことも確認する。
 */
class WoodRepairListenerQuickRepairTest {

    private static final String UNLOCK = "wood-repair-unlock";

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private CraftingFeaturesConfig features;
    private WoodRepairListener listener;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        features = mock(CraftingFeaturesConfig.class);
        listener = new WoodRepairListener(dedicatedEffects, features);
        player = server.addPlayer();
        when(dedicatedEffects.isActive(any(), eq(UNLOCK))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Stamps a catalog id onto the item via the real {@link com.trinityforge.pdc.ItemData} PDC path. */
    private ItemStack materialStack(String catalogId, int amount) {
        ItemStack stack = new ItemStack(Material.STICK, amount);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(catalogId);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack damagedSword(int damage) {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        ((Damageable) meta).setDamage(damage);
        stack.setItemMeta(meta);
        return stack;
    }

    private InventoryClickEvent clickEvent(ItemStack cursor, ItemStack target, ClickType click) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getType()).thenReturn(InventoryType.CRAFTING);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClick()).thenReturn(click);
        when(event.getCursor()).thenReturn(cursor);
        when(event.getCurrentItem()).thenReturn(target);
        return event;
    }

    @Test
    void quickRepairMaterialRepairsTargetAndConsumesOneFromCursor() {
        when(features.woodRepairMaterial("compressed_wood_1x"))
                .thenReturn(new CraftingFeaturesConfig.WoodRepairMaterial(200, true));
        ItemStack cursor = materialStack("compressed_wood_1x", 5);
        ItemStack target = damagedSword(300);
        InventoryClickEvent event = clickEvent(cursor, target, ClickType.LEFT);

        listener.onInventoryClick(event);

        verify(event, times(1)).setCancelled(true);
        org.mockito.ArgumentCaptor<ItemStack> repairedCaptor = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
        verify(event).setCurrentItem(repairedCaptor.capture());
        int newDamage = ((Damageable) repairedCaptor.getValue().getItemMeta()).getDamage();
        assertEquals(100, newDamage, "300 damage - min(300,200) durability repair = 100 remaining");
        assertEquals(4, player.getItemOnCursor().getAmount(), "cursor material stack decremented by 1");
    }

    @Test
    void cursorClearedWhenLastMaterialIsConsumed() {
        when(features.woodRepairMaterial("compressed_wood_1x"))
                .thenReturn(new CraftingFeaturesConfig.WoodRepairMaterial(200, true));
        ItemStack cursor = materialStack("compressed_wood_1x", 1);
        ItemStack target = damagedSword(50);
        InventoryClickEvent event = clickEvent(cursor, target, ClickType.RIGHT);

        listener.onInventoryClick(event);

        verify(event, times(1)).setCancelled(true);
        ItemStack remainingCursor = player.getItemOnCursor();
        boolean cleared = remainingCursor == null || remainingCursor.getType() == Material.AIR;
        org.junit.jupiter.api.Assertions.assertTrue(cleared,
                "cursor must be cleared once the last material unit is consumed");
    }

    @Test
    void quickRepairFalseMaterialDoesNotCancelOrRepair() {
        when(features.woodRepairMaterial("compressed_wood_1x"))
                .thenReturn(new CraftingFeaturesConfig.WoodRepairMaterial(200, false));
        ItemStack cursor = materialStack("compressed_wood_1x", 5);
        ItemStack target = damagedSword(300);
        InventoryClickEvent event = clickEvent(cursor, target, ClickType.LEFT);

        listener.onInventoryClick(event);

        verify(event, never()).setCancelled(true);
        verify(event, never()).setCurrentItem(any());
    }

    @Test
    void gateOffPlayerLacksUnlockDoesNotRepair() {
        when(dedicatedEffects.isActive(any(), eq(UNLOCK))).thenReturn(false);
        when(features.woodRepairMaterial("compressed_wood_1x"))
                .thenReturn(new CraftingFeaturesConfig.WoodRepairMaterial(200, true));
        ItemStack cursor = materialStack("compressed_wood_1x", 5);
        ItemStack target = damagedSword(300);
        InventoryClickEvent event = clickEvent(cursor, target, ClickType.LEFT);

        listener.onInventoryClick(event);

        verify(event, never()).setCancelled(true);
        verify(event, never()).setCurrentItem(any());
    }
}
