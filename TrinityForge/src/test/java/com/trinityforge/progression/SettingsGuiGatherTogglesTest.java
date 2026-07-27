package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SettingsGui}: 2026-07-25 gather-rework-active-framework §2 B-2 の採取プレイヤートグル4種
 * (vein-mining/tree-fell/auto-replant/area-harvest) — open()でON/OFF表示され、クリックで反転すること。
 */
class SettingsGuiGatherTogglesTest {

    private ServerMock server;
    private SettingsGui gui;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        SpecialRewardsConfig config = mock(SpecialRewardsConfig.class);
        when(config.titles()).thenReturn(Map.of());
        when(config.particles()).thenReturn(Map.of());
        SpecialRewardService rewardService = mock(SpecialRewardService.class);
        gui = new SettingsGui(MockBukkit.createMockPlugin(), config, rewardService);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void openDefaultsEveryGatherToggleToOn() {
        gui.open(player);
        Inventory open = player.getOpenInventory().getTopInventory();

        // slots 5-8 per SettingsGui: vein-mining/tree-fell/auto-replant/area-harvest.
        for (int slot = 5; slot <= 8; slot++) {
            ItemStack item = open.getItem(slot);
            assertTrue(item != null && item.hasItemMeta()
                            && item.getItemMeta().displayName() != null
                            && item.getItemMeta().displayName().toString().contains("ON"),
                    "slot " + slot + " must render as ON by default");
        }
    }

    @Test
    void clickingVeinMiningToggleFlipsPlayerDataAndRerenders() {
        gui.open(player);
        Inventory open = player.getOpenInventory().getTopInventory();
        ItemStack veinMiningButton = open.getItem(5);

        InventoryClickEvent event = new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, 5,
                org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);

        assertTrue(PlayerData.of(player).veinMiningEnabled());

        gui.onClick(event);

        assertFalse(PlayerData.of(player).veinMiningEnabled());
    }
}
