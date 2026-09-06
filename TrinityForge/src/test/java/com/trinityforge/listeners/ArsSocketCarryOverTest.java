package com.trinityforge.listeners;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 強化で作り直した装備からバックパック／スレッド装着データが消えないこと。
 */
class ArsSocketCarryOverTest {

    private static final NamespacedKey THREAD_SLOTS = new NamespacedKey("arspaper", "thread_slots");
    private static final NamespacedKey BACKPACK_DATA = new NamespacedKey("arspaper", "backpack_data");
    private static final NamespacedKey CUSTOM_ITEM_ID = new NamespacedKey("arspaper", "custom_item_id");
    private static final NamespacedKey THREAD_SLOT_ROLLS = new NamespacedKey("arspaper", "thread_slot_rolls");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        server = null;
    }

    @Test
    void copyKeepsBackpackContentsAndSocketedThreads() {
        ItemStack oldArmor = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemMeta oldMeta = oldArmor.getItemMeta();
        oldMeta.getPersistentDataContainer().set(THREAD_SLOTS, PersistentDataType.STRING, "[\"backpack\"]");
        oldMeta.getPersistentDataContainer().set(BACKPACK_DATA, PersistentDataType.STRING, "{\"0\":\"stone\"}");
        oldMeta.getPersistentDataContainer().set(THREAD_SLOT_ROLLS, PersistentDataType.STRING, "[\"12:3\"]");
        oldMeta.getPersistentDataContainer().set(CUSTOM_ITEM_ID, PersistentDataType.STRING, "mage_novice");
        oldArmor.setItemMeta(oldMeta);

        ItemStack upgraded = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemMeta newMeta = upgraded.getItemMeta();
        newMeta.getPersistentDataContainer().set(CUSTOM_ITEM_ID, PersistentDataType.STRING, "mage_apprentice");
        upgraded.setItemMeta(newMeta);

        ArsSocketCarryOver.copy(oldArmor, upgraded);

        ItemMeta result = upgraded.getItemMeta();
        assertEquals("[\"backpack\"]",
                result.getPersistentDataContainer().get(THREAD_SLOTS, PersistentDataType.STRING));
        assertEquals("{\"0\":\"stone\"}",
                result.getPersistentDataContainer().get(BACKPACK_DATA, PersistentDataType.STRING));
        assertEquals("[\"12:3\"]",
                result.getPersistentDataContainer().get(THREAD_SLOT_ROLLS, PersistentDataType.STRING));
        assertEquals("mage_apprentice",
                result.getPersistentDataContainer().get(CUSTOM_ITEM_ID, PersistentDataType.STRING),
                "新しい成果物の identity は上書きしてはいけない");
    }

    @Test
    void copyDoesNotInventSocketsWhenSourceHasNone() {
        ItemStack plain = new ItemStack(Material.DIAMOND_CHESTPLATE);
        ItemStack result = new ItemStack(Material.NETHERITE_CHESTPLATE);
        ArsSocketCarryOver.copy(plain, result);
        assertNull(result.getItemMeta().getPersistentDataContainer()
                .get(THREAD_SLOTS, PersistentDataType.STRING));
    }
}
