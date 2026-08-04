package com.trinityforge.items;

import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link EquipmentTicketItemListener}: カタログIDに一致する券を右クリックしたら、対応する
 * {@link EquipmentTicketEffect} で {@link EquipmentTicketGui} を開くことの回帰テスト。
 * 空クリック({@code ignoreCancelled}罠)/DENY/未登録カタログIDの3経路を検証する。
 */
class EquipmentTicketItemListenerTest {

    private static final String STAT_REROLL_ID = "stat_reroll_ticket";
    private static final String QUALITY_UPGRADE_ID = "quality_upgrade_ticket";

    private ServerMock server;
    private PlayerMock player;
    private EquipmentTicketGui gui;
    private EquipmentTicketEffect statRerollEffect;
    private EquipmentTicketEffect qualityUpgradeEffect;
    private EquipmentTicketItemListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        gui = mock(EquipmentTicketGui.class);
        statRerollEffect = mock(EquipmentTicketEffect.class);
        org.mockito.Mockito.when(statRerollEffect.catalogId()).thenReturn(STAT_REROLL_ID);
        qualityUpgradeEffect = mock(EquipmentTicketEffect.class);
        org.mockito.Mockito.when(qualityUpgradeEffect.catalogId()).thenReturn(QUALITY_UPGRADE_ID);
        listener = new EquipmentTicketItemListener(gui, List.of(statRerollEffect, qualityUpgradeEffect));
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack ticketStack(String catalogId) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(catalogId);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 虚空への右クリック: クリックしたブロックが {@code null} の {@link PlayerInteractEvent}。 */
    private static PlayerInteractEvent rightClickAir(PlayerMock player, ItemStack held) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                held, null, BlockFace.SELF, EquipmentSlot.HAND);
    }

    @Test
    @DisplayName("券のカタログIDに応じて正しいeffectでGUIを開く(複数券の中から選ぶ)")
    void routesToTheEffectMatchingCatalogId() {
        ItemStack ticket = ticketStack(QUALITY_UPGRADE_ID);
        player.getInventory().setItemInMainHand(ticket);
        PlayerInteractEvent event = rightClickAir(player, ticket);

        listener.onInteract(event);

        verify(gui).open(eq(player), eq(qualityUpgradeEffect));
        verify(gui, never()).open(eq(player), eq(statRerollEffect));
    }

    @Test
    @DisplayName("ignoreCancelledを付け直すとRIGHT_CLICK_AIRが届かなくなるため禁止")
    void onInteractMustNotIgnoreCancelled() throws NoSuchMethodException {
        EventHandler annotation = EquipmentTicketItemListener.class
                .getMethod("onInteract", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);

        assertFalse(annotation.ignoreCancelled(),
                "ignoreCancelled=true を付けると RIGHT_CLICK_AIR が配送されなくなる"
                        + "(DungeonKeyItemListener/GachaListenerと同じ罠)");
    }

    @Test
    @DisplayName("他プラグインがアイテム使用を拒否した(useItemInHand=DENY)場合はGUIを開かない")
    void deniedItemUseDoesNotOpenGui() {
        ItemStack ticket = ticketStack(STAT_REROLL_ID);
        player.getInventory().setItemInMainHand(ticket);
        PlayerInteractEvent event = rightClickAir(player, ticket);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

        listener.onInteract(event);

        verify(gui, never()).open(any(), any());
    }

    @Test
    @DisplayName("未登録のカタログIDを持っての右クリックでは何もしない")
    void unknownCatalogIdDoesNothing() {
        ItemStack unrelated = ticketStack("some_other_item");
        player.getInventory().setItemInMainHand(unrelated);
        PlayerInteractEvent event = rightClickAir(player, unrelated);

        listener.onInteract(event);

        verify(gui, never()).open(any(), any());
    }
}
