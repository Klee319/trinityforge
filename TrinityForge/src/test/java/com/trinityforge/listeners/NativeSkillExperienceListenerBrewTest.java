package com.trinityforge.listeners;

import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NativeSkillExperienceListener#onBrew} after the {@link BrewOwnership} extraction: same
 * behaviour as before the refactor (manual/automated multiplier, no grant without a recorded owner,
 * and the owner PDC is always cleared afterward win-or-lose).
 */
class NativeSkillExperienceListenerBrewTest {

    private static final SkillCatalogEntry ALCHEMY_ENTRY = new SkillCatalogEntry(
            "ALCHEMY", 100, "1", level -> 1L, Map.of(),
            Map.of("alchemy.brew", 25.0, "alchemy.manual_mult", 2.0, "alchemy.auto_mult", 0.25));

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private BrewingStand stand;
    private BrewOwnership ownership;
    private NativeExperienceDispatcher dispatcher;
    private NativeSkillExperienceListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.BREWING_STAND);
        stand = (BrewingStand) block.getState();
        ownership = new BrewOwnership(plugin);

        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.ALCHEMY)).thenReturn(ALCHEMY_ENTRY);
        PlacedBlockTracker tracker = mock(PlacedBlockTracker.class);
        dispatcher = mock(NativeExperienceDispatcher.class);
        listener = new NativeSkillExperienceListener(plugin, dispatcher, catalog, tracker);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private BrewEvent brewEvent() {
        BrewerInventory inv = stand.getInventory();
        List<ItemStack> results = new ArrayList<>();
        return new BrewEvent(stand.getBlock(), inv, results, 20);
    }

    @Test
    void manualOwnerGrantsWithManualMultiplier() {
        stand.getPersistentDataContainer().set(
                ownership.lastBrewerKey(), PersistentDataType.STRING, player.getUniqueId().toString());
        stand.getPersistentDataContainer().set(
                ownership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_MANUAL);
        stand.update();

        listener.onBrew(brewEvent());

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ALCHEMY, 50.0); // 25 * 2.0
    }

    @Test
    void automatedOwnerGrantsWithAutoMultiplier() {
        stand.getPersistentDataContainer().set(
                ownership.lastBrewerKey(), PersistentDataType.STRING, player.getUniqueId().toString());
        stand.getPersistentDataContainer().set(
                ownership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_AUTO);
        stand.update();

        listener.onBrew(brewEvent());

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ALCHEMY, 6.25); // 25 * 0.25
    }

    @Test
    void noOwnerGrantsNothing() {
        listener.onBrew(brewEvent());

        verify(dispatcher, never()).grant(any(), any(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void ownerPdcIsAlwaysClearedAfterward() {
        stand.getPersistentDataContainer().set(
                ownership.lastBrewerKey(), PersistentDataType.STRING, player.getUniqueId().toString());
        stand.getPersistentDataContainer().set(
                ownership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_MANUAL);
        stand.update();

        listener.onBrew(brewEvent());

        org.junit.jupiter.api.Assertions.assertTrue(ownership.ownerOf(stand).isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(ownership.isAutomated(stand));
    }
}
