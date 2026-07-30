package com.trinityforge.listeners;

import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Armor-slot hotbar/offhand swaps must validate the item that will enter the slot, not the empty
 * cursor or the armor item currently being replaced.
 */
class OwnerBindListenerArmorSwapTest {

    private ServerMock server;
    private PlayerMock player;
    private OwnerBindListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        listener = new OwnerBindListener();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack armorBoundToSomeoneElse() {
        ItemStack armor = new ItemStack(Material.DIAMOND_HELMET);
        ItemMeta meta = armor.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setBindType(BindType.OWNER_BOUND);
        data.setOwner(UUID.randomUUID());
        armor.setItemMeta(meta);
        return armor;
    }

    private InventoryClickEvent armorSlotClick(ClickType click) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(5);
        when(event.getSlot()).thenReturn(39);
        when(event.getClick()).thenReturn(click);
        when(event.getCursor()).thenReturn(new ItemStack(Material.AIR));
        when(event.getCurrentItem()).thenReturn(new ItemStack(Material.AIR));
        return event;
    }

    @Test
    void numberKeyChecksBoundHotbarItemEnteringArmorSlot() {
        player.getInventory().setItem(2, armorBoundToSomeoneElse());
        InventoryClickEvent event = armorSlotClick(ClickType.NUMBER_KEY);
        when(event.getHotbarButton()).thenReturn(2);

        listener.onInventoryClick(event);

        verify(event).setCancelled(true);
    }

    @Test
    void swapOffhandChecksBoundOffhandItemEnteringArmorSlot() {
        player.getInventory().setItemInOffHand(armorBoundToSomeoneElse());
        InventoryClickEvent event = armorSlotClick(ClickType.SWAP_OFFHAND);

        listener.onInventoryClick(event);

        verify(event).setCancelled(true);
    }
}
