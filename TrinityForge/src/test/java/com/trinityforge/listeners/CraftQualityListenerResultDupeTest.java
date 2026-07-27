package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.stats.CraftQualityService;
import com.trinityforge.stats.CraftRollMods;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemStatProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.CraftItemEvent;
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

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-07-28 実サーバで報告された複製＋ちらつきの回帰ガード:
 * 「クラフトのリザルトからアイテムを取ろうとするとちらつき、素材が減らないまま無限に回収できる
 * (カスタムアイテムで確認)」。
 *
 * <p><b>真因</b>: CraftBukkit の {@code handleContainerClick} は <b>イベント発火 → バニラの
 * {@code AbstractContainerMenu.clicked(...)}</b> の順で走る。そのため {@link CraftItemEvent} の
 * ハンドラ内で {@code player.setItemOnCursor(...)} を呼ぶと、バニラは「カーソルが空 → 結果枠を取る」
 * ではなく「カーソルに同じ品がある → マージする」経路へ入り、最大スタック 1 の装備では取得上限が
 * {@code 1 - 1 = 0} になって {@code Slot#tryRemove} が空 Optional を返す。
 * その結果 <b>{@code ResultSlot#onTake} が呼ばれず素材が一切消費されない</b>のに、プレイヤーの手には
 * リスナーが載せた完成品が残る = 本物のサーバ側複製。クライアントは素材が減った前提で描画しているので、
 * 直後の同期で盤面が巻き戻り「ちらつき」にもなる。
 *
 * <p>したがってこのテストの主眼は「{@link CraftQualityListener#onCraft} がカーソルに触らないこと」。
 */
class CraftQualityListenerResultDupeTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private CraftQualityListener listener(ItemStatsConfig itemStats) {
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin();
        CraftQualityService craftQualityService = mock(CraftQualityService.class);
        when(craftQualityService.rollQuality(any(), any(), anyInt())).thenReturn(0);
        when(craftQualityService.craftRollMods(any())).thenReturn(new CraftRollMods(0, 0, 0));
        return new CraftQualityListener(plugin, mock(ItemFactory.class), craftQualityService,
                new CraftQualityConfig(), mock(SkillExpConfig.class), mock(ItemCatalogConfig.class),
                itemStats);
    }

    /** item-stats プロファイルを持つ装備(=品質刻印の対象)としてクラフトされる場面。 */
    private CraftItemEvent craftEvent(Player player, ItemStack result, ItemStatsConfig itemStats) {
        when(itemStats.profileFor(any(), any())).thenReturn(Optional.of(mock(ItemStatProfile.class)));
        when(itemStats.qualityModeOffsetFor(any(), any())).thenReturn(0);

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);

        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(null);
        return event;
    }

    @Test
    @DisplayName("クラフト刻印はカーソルに触らない(触るとバニラが素材を消費しなくなり複製する)")
    void onCraftNeverWritesTheCursor() {
        ItemStatsConfig itemStats = mock(ItemStatsConfig.class);
        Player player = mock(Player.class);
        when(player.getItemOnCursor()).thenReturn(new ItemStack(Material.AIR));
        CraftItemEvent event = craftEvent(player, new ItemStack(Material.DIAMOND_SWORD), itemStats);

        listener(itemStats).onCraft(event);

        verify(player, never()).setItemOnCursor(any());
    }

    @Test
    @DisplayName("刻印済みの完成品は結果枠へ差し込まれる(バニラがそこから取ってカーソルへ渡す)")
    void onCraftStampsTheResultSlot() {
        ItemStatsConfig itemStats = mock(ItemStatsConfig.class);
        Player player = mock(Player.class);
        when(player.getItemOnCursor()).thenReturn(new ItemStack(Material.AIR));
        CraftItemEvent event = craftEvent(player, new ItemStack(Material.DIAMOND_SWORD), itemStats);

        listener(itemStats).onCraft(event);

        verify(event).setCurrentItem(any(ItemStack.class));
    }

    // ------------------------------------------------------------------
    // ドラッグ経路: 結果枠は quick-craft の置き先になれないので複製経路にならない。
    // 「カーソル == 結果枠なら落とす」ヒューリスティックは通常操作を巻き込みちらつきを生むため撤去済み。
    // ------------------------------------------------------------------

    private InventoryDragEvent dragEvent(ItemStack result, ItemStack cursor, Set<Integer> rawSlots) {
        Inventory top = mock(CraftingInventory.class);
        when(((CraftingInventory) top).getResult()).thenReturn(result);
        when(top.getType()).thenReturn(InventoryType.WORKBENCH);

        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.countSlots()).thenReturn(46);
        when(view.getSlotType(anyInt())).thenReturn(InventoryType.SlotType.CONTAINER);
        when(view.getSlotType(0)).thenReturn(InventoryType.SlotType.RESULT);

        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getOldCursor()).thenReturn(cursor);
        when(event.getRawSlots()).thenReturn(rawSlots);
        return event;
    }

    @Test
    @DisplayName("結果枠を置き先に含むドラッグはキャンセルする")
    void dragIntoResultSlotIsCancelled() {
        InventoryDragEvent event = dragEvent(new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.DIAMOND_SWORD), Set.of(0));

        listener(mock(ItemStatsConfig.class)).onCraftResultDrag(event);

        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("作ったばかりの品を持ったままの通常ドラッグはキャンセルしない(旧ガードの誤爆＝ちらつきの一因)")
    void dragWhileHoldingACopyOfTheResultIsAllowed() {
        InventoryDragEvent event = dragEvent(new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.DIAMOND_SWORD), Set.of(20, 21));

        listener(mock(ItemStatsConfig.class)).onCraftResultDrag(event);

        verify(event, never()).setCancelled(true);
    }
}
