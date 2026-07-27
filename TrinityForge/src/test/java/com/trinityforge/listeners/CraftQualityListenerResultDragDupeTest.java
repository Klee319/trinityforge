package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.stats.CraftQualityService;
import com.trinityforge.stats.ItemFactory;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-07-28 実サーバで報告された複製の回帰ガード:
 * 「クラフト時にリザルト画面からドラッグでアイテムを回収すると、リザルトからアイテムが消えず無限に増やせる」
 * (再ログイン後も残る=クライアント表示ズレではなく本物のサーバ側複製)。
 *
 * <p>原因は {@link InventoryDragEvent#getRawSlots()} が<b>置き先スロットしか持たない</b>こと。
 * 結果枠を起点にしたドラッグでは結果枠が rawSlots に現れないため、置き先だけを見ていた旧ガードを
 * 素通りし、しかも {@code CraftItemEvent} を経由しないので素材も消費されない。
 * {@link CraftQualityListener#draggedOutOfCraftingResult} は「カーソルの中身 == 結果枠の中身」で
 * この経路を検出する。
 */
class CraftQualityListenerResultDragDupeTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private CraftQualityListener listener() {
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin();
        CraftQualityService craftQualityService = mock(CraftQualityService.class);
        when(craftQualityService.rollQuality(any(), any(), anyInt())).thenReturn(0);
        return new CraftQualityListener(plugin, mock(ItemFactory.class), craftQualityService,
                new CraftQualityConfig(), mock(SkillExpConfig.class), mock(ItemCatalogConfig.class),
                mock(ItemStatsConfig.class));
    }

    /** 結果枠に {@code result} が乗った作業台ビューでの、カーソル {@code cursor} のドラッグ。 */
    private InventoryDragEvent dragEvent(ItemStack result, ItemStack cursor, InventoryType topType) {
        Inventory top = topType == InventoryType.WORKBENCH || topType == InventoryType.CRAFTING
                ? mock(CraftingInventory.class)
                : mock(Inventory.class);
        if (top instanceof CraftingInventory crafting) {
            when(crafting.getResult()).thenReturn(result);
        }
        when(top.getType()).thenReturn(topType);

        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);

        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getOldCursor()).thenReturn(cursor);
        when(event.getRawSlots()).thenReturn(Set.of()); // 置き先はインベントリ側のみ(結果枠は入らない)
        return event;
    }

    @Test
    @DisplayName("結果枠と同一の品をカーソルに乗せたドラッグは結果枠由来とみなす")
    void cursorMatchingResultIsDetected() {
        InventoryDragEvent event = dragEvent(new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.DIAMOND_SWORD), InventoryType.WORKBENCH);

        assertTrue(CraftQualityListener.draggedOutOfCraftingResult(event, event.getView()));
    }

    @Test
    @DisplayName("2x2 インベントリクラフトでも同じく検出する")
    void detectedInPlayerCraftingGrid() {
        InventoryDragEvent event = dragEvent(new ItemStack(Material.STICK),
                new ItemStack(Material.STICK), InventoryType.CRAFTING);

        assertTrue(CraftQualityListener.draggedOutOfCraftingResult(event, event.getView()));
    }

    @Test
    @DisplayName("結果枠と別の品をドラッグするのは通常操作なので通す")
    void differentItemIsAllowed() {
        InventoryDragEvent event = dragEvent(new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.DIRT), InventoryType.WORKBENCH);

        assertFalse(CraftQualityListener.draggedOutOfCraftingResult(event, event.getView()));
    }

    @Test
    @DisplayName("結果枠が空なら何をドラッグしても通す")
    void emptyResultIsAllowed() {
        InventoryDragEvent event = dragEvent(new ItemStack(Material.AIR),
                new ItemStack(Material.DIRT), InventoryType.WORKBENCH);

        assertFalse(CraftQualityListener.draggedOutOfCraftingResult(event, event.getView()));
    }

    @Test
    @DisplayName("カーソルが空なら通す")
    void emptyCursorIsAllowed() {
        InventoryDragEvent event = dragEvent(new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.AIR), InventoryType.WORKBENCH);

        assertFalse(CraftQualityListener.draggedOutOfCraftingResult(event, event.getView()));
    }

    @Test
    @DisplayName("クラフト画面以外(チェスト等)には一切干渉しない")
    void nonCraftingViewIsUntouched() {
        InventoryDragEvent event = dragEvent(null, new ItemStack(Material.DIAMOND_SWORD),
                InventoryType.CHEST);

        assertFalse(CraftQualityListener.draggedOutOfCraftingResult(event, event.getView()));
    }

    @Test
    @DisplayName("結果枠由来のドラッグはイベントごとキャンセルされる")
    void listenerCancelsResultOriginatedDrag() {
        InventoryDragEvent event = dragEvent(new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.DIAMOND_SWORD), InventoryType.WORKBENCH);
        PlayerMock player = server.addPlayer();
        when(event.getWhoClicked()).thenReturn(player);

        listener().onCraftResultDrag(event);

        org.mockito.Mockito.verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("通常のドラッグはキャンセルされない")
    void listenerAllowsNormalDrag() {
        InventoryDragEvent event = dragEvent(new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.DIRT), InventoryType.WORKBENCH);

        listener().onCraftResultDrag(event);

        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }
}
