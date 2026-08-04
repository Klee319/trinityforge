package com.trinityforge.items;

import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.RoleSelectGui;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RoleTicketItemListener}: 職業付け替えの証(role_reselect_ticket)を持って右クリックしたら
 * {@link RoleSelectGui} を券モード({@code ticketMode=true})で開くことの回帰テスト。
 *
 * <p>{@code ignoreCancelled=true} を付け直すと {@code RIGHT_CLICK_AIR} が配送されなくなる罠
 * ({@code docs/agent-context/common-traps.md})の固定は {@code GachaListenerVoidClickAndDisplayNameTest}
 * と同じ作法(実 {@link PlayerInteractEvent} を作り {@code isCancelled()==true} を先に確認する)で行う。
 */
class RoleTicketItemListenerTest {

    private ServerMock server;
    private PlayerMock player;
    private RoleSelectGui roleSelectGui;
    private RoleTicketItemListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        roleSelectGui = mock(RoleSelectGui.class);
        listener = new RoleTicketItemListener(roleSelectGui);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack ticketStack() {
        ItemStack stack = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(RoleSelectGui.TICKET_CATALOG_ID);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 虚空への右クリック: クリックしたブロックが {@code null} の {@link PlayerInteractEvent}。 */
    private static PlayerInteractEvent rightClickAir(PlayerMock player, ItemStack held) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                held, null, BlockFace.SELF, EquipmentSlot.HAND);
    }

    @Test
    @DisplayName("虚空への右クリックでも券モードでRoleSelectGuiを開く(空クリックは生成直後からisCancelled=true)")
    void voidRightClickOpensRoleSelectGuiInTicketMode() {
        ItemStack ticket = ticketStack();
        player.getInventory().setItemInMainHand(ticket);
        PlayerInteractEvent event = rightClickAir(player, ticket);

        assertTrue(event.isCancelled(),
                "RIGHT_CLICK_AIR は blockClicked==null なので生成時点で isCancelled()==true になる"
                        + "(Bukkitの仕様)。ignoreCancelled=true だとここで弾かれてonInteractへ届かない");

        listener.onInteract(event);

        verify(roleSelectGui).open(eq(player), eq(true));
    }

    @Test
    @DisplayName("ignoreCancelledを付け直すとRIGHT_CLICK_AIRが届かなくなるため禁止")
    void onInteractMustNotIgnoreCancelled() throws NoSuchMethodException {
        EventHandler annotation = RoleTicketItemListener.class
                .getMethod("onInteract", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);

        assertFalse(annotation.ignoreCancelled(),
                "ignoreCancelled=true を付けると RIGHT_CLICK_AIR が配送されなくなる"
                        + "(DungeonKeyItemListener/GachaListenerと同じ罠)");
    }

    @Test
    @DisplayName("他プラグインがアイテム使用を拒否した(useItemInHand=DENY)場合はGUIを開かない")
    void deniedItemUseDoesNotOpenGui() {
        ItemStack ticket = ticketStack();
        player.getInventory().setItemInMainHand(ticket);
        PlayerInteractEvent event = rightClickAir(player, ticket);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

        listener.onInteract(event);

        verify(roleSelectGui, never()).open(any(), eq(true));
    }

    @Test
    @DisplayName("券以外のアイテムを持っての右クリックでは何もしない")
    void nonTicketItemDoesNothing() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        player.getInventory().setItemInMainHand(sword);
        PlayerInteractEvent event = rightClickAir(player, sword);

        listener.onInteract(event);

        verify(roleSelectGui, never()).open(any(), eq(true));
    }
}
