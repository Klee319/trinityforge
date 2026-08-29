package com.trinityforge.listeners;

import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogAnvilEnchantPreserveTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("広辞苑(BOOK) + エンチャント本 → 結果は BOOK のまま sharpness が乗る")
    void catalogBookKeepsIdentityWhenVanillaWouldEmitEnchantedBook() {
        ItemStack koujien = new ItemStack(Material.BOOK);
        ItemMeta meta = koujien.getItemMeta();
        meta.setCustomModelData(100004);
        ItemData.of(meta).setCatalogId("koujien");
        koujien.setItemMeta(meta);

        ItemStack vanillaResult = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta stored = (EnchantmentStorageMeta) vanillaResult.getItemMeta();
        stored.addStoredEnchant(Enchantment.SHARPNESS, 5, true);
        vanillaResult.setItemMeta(stored);

        ItemStack preserved = CatalogAnvilEnchantPreserve.preserveIfTypeChanged(koujien, vanillaResult);

        assertEquals(Material.BOOK, preserved.getType(), "バニラの ENCHANTED_BOOK 差し替えを残してはいけない");
        assertEquals(100004, preserved.getItemMeta().getCustomModelData());
        assertEquals("koujien", ItemData.of(preserved.getItemMeta()).catalogId().orElse(null));
        assertEquals(5, preserved.getEnchantmentLevel(Enchantment.SHARPNESS));
    }

    @Test
    @DisplayName("type が同じ剣＋本でも、結果から落ちた品質とロールを左枠から戻す")
    void sameTypeResultGetsRollAndQualityBackFromTheLeftItem() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.setCustomModelData(111);
        ItemData data = ItemData.of(meta);
        data.setCatalogId("guard_blade");
        data.setRollSeed(99L);
        data.setQuality(12);
        sword.setItemMeta(meta);

        ItemStack vanillaResult = new ItemStack(Material.DIAMOND_SWORD);
        vanillaResult.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);

        ItemStack preserved = CatalogAnvilEnchantPreserve.preserveIfTypeChanged(sword, vanillaResult);

        ItemData restored = ItemData.of(preserved.getItemMeta());
        assertEquals(99L, restored.rollSeed().orElseThrow());
        assertEquals(12, restored.quality());
        assertEquals("guard_blade", restored.catalogId().orElseThrow());
        assertEquals(5, preserved.getEnchantmentLevel(Enchantment.SHARPNESS));
    }

    @Test
    @DisplayName("type が変わらない剣への付与はそのまま通す")
    void sameTypeResultIsLeftAlone() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemStack result = new ItemStack(Material.DIAMOND_SWORD);
        result.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);

        assertSame(result, CatalogAnvilEnchantPreserve.preserveIfTypeChanged(sword, result));
    }
}
