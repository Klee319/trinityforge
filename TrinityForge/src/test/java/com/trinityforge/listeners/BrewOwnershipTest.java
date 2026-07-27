package com.trinityforge.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BrewOwnership}: 醸造所有者PDCの共有読み取り。書き込みは
 * {@link NativeSkillExperienceListener} 側(rememberBrewer/markAutomatedBrew)が担うため、ここではPDCへ
 * 直接書いた値を正しく読める(かつ壊れたUUID文字列は無視する)ことだけを検証する。
 */
class BrewOwnershipTest {

    private ServerMock server;
    private Plugin plugin;
    private BrewingStand stand;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Block block = server.addSimpleWorld("world").getBlockAt(0, 64, 0);
        block.setType(Material.BREWING_STAND);
        stand = (BrewingStand) block.getState();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void ownerOfIsEmptyWhenNeverWritten() {
        BrewOwnership ownership = new BrewOwnership(plugin);
        assertTrue(ownership.ownerOf(stand).isEmpty());
        assertFalse(ownership.isAutomated(stand));
    }

    @Test
    void ownerOfReadsUuidWrittenByAnotherInstanceOfTheSamePlugin() {
        UUID id = UUID.randomUUID();
        // Simulates NativeSkillExperienceListener#rememberBrewer writing via its own BrewOwnership.
        BrewOwnership writer = new BrewOwnership(plugin);
        stand.getPersistentDataContainer().set(
                writer.lastBrewerKey(), PersistentDataType.STRING, id.toString());
        stand.getPersistentDataContainer().set(
                writer.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_MANUAL);
        stand.update();

        // A separately-constructed BrewOwnership (as used by PotionQualityListener) must see the same value.
        BrewOwnership reader = new BrewOwnership(plugin);
        Optional<UUID> owner = reader.ownerOf(stand);
        assertTrue(owner.isPresent());
        assertEquals(id, owner.get());
        assertFalse(reader.isAutomated(stand));
    }

    @Test
    void isAutomatedReflectsHopperMode() {
        BrewOwnership ownership = new BrewOwnership(plugin);
        stand.getPersistentDataContainer().set(
                ownership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_AUTO);
        stand.update();

        assertTrue(ownership.isAutomated(stand));
    }

    @Test
    void malformedUuidIsIgnoredNotThrown() {
        BrewOwnership ownership = new BrewOwnership(plugin);
        stand.getPersistentDataContainer().set(
                ownership.lastBrewerKey(), PersistentDataType.STRING, "not-a-uuid");
        stand.update();

        assertTrue(ownership.ownerOf(stand).isEmpty());
    }
}
