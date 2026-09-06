package com.trinityforge.stats;

import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemIdentityCopyTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void copiesRollQualityAndCatalogOntoABareResult() {
        ItemStack from = new ItemStack(Material.DIAMOND_SWORD);
        from.editMeta(meta -> {
            ItemData data = ItemData.of(meta);
            data.setRollSeed(42L);
            data.setQuality(11);
            data.setCatalogId("diamond_blade");
        });
        ItemStack to = new ItemStack(Material.DIAMOND_SWORD);
        to.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 5);

        ItemIdentityCopy.copyRollQualityCatalog(from, to);

        ItemData copied = ItemData.of(to.getItemMeta());
        assertEquals(42L, copied.rollSeed().orElseThrow());
        assertTrue(copied.hasQuality());
        assertEquals(11, copied.quality());
        assertEquals("diamond_blade", copied.catalogId().orElseThrow());
        assertEquals(5, to.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS));
    }

    @Test
    void doesNotInventQualityWhenSourceNeverHadTheKey() {
        ItemStack from = new ItemStack(Material.DIAMOND_SWORD);
        ItemStack to = new ItemStack(Material.DIAMOND_SWORD);
        to.editMeta(meta -> ItemData.of(meta).setQuality(3));

        ItemIdentityCopy.copyRollQualityCatalog(from, to);

        assertTrue(ItemData.of(to.getItemMeta()).hasQuality());
        assertEquals(3, ItemData.of(to.getItemMeta()).quality());
    }

    @Test
    void qualityZeroIsStillAStampAndIsCopied() {
        ItemStack from = new ItemStack(Material.DIAMOND_SWORD);
        from.editMeta(meta -> {
            ItemData data = ItemData.of(meta);
            data.setRollSeed(1L);
            data.setQuality(0);
        });
        ItemStack to = new ItemStack(Material.DIAMOND_SWORD);

        ItemIdentityCopy.copyRollQualityCatalog(from, to);

        ItemMeta meta = to.getItemMeta();
        assertTrue(ItemData.of(meta).hasQuality());
        assertEquals(0, ItemData.of(meta).quality());
        assertFalse(ItemData.of(new ItemStack(Material.DIAMOND_SWORD).getItemMeta()).hasQuality());
    }
}
