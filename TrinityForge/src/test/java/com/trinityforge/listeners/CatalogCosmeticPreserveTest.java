package com.trinityforge.listeners;

import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * カタログ革防具の染色・トリムで個体データが壊れないこと。create/stamp を通すと
 * rollSeed や色が作り直されるので、clone + 色/trim 転写だけを固定する。
 */
class CatalogCosmeticPreserveTest {

    private static final long ROLL = 424242L;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack catalogLeather(Color color) {
        ItemStack stack = new ItemStack(Material.LEATHER_CHESTPLATE);
        LeatherArmorMeta meta = (LeatherArmorMeta) stack.getItemMeta();
        meta.setCustomModelData(9001);
        meta.setColor(color);
        ItemData data = ItemData.of(meta);
        data.setCatalogId("light_leather_chest");
        data.setRollSeed(ROLL);
        data.setQuality(11);
        data.setBindType(BindType.SOULBOUND);
        stack.setItemMeta(meta);
        return stack;
    }

    @Test
    void leatherDyeCraftDetectsArmorPlusDyesOnly() {
        ItemStack armor = catalogLeather(Color.WHITE);
        ItemStack[] matrix = new ItemStack[] {
                armor, new ItemStack(Material.RED_DYE), null, null, null, null, null, null, null
        };
        ItemStack vanilla = new ItemStack(Material.LEATHER_CHESTPLATE);
        LeatherArmorMeta dyed = (LeatherArmorMeta) vanilla.getItemMeta();
        dyed.setColor(Color.RED);
        vanilla.setItemMeta(dyed);

        assertTrue(CatalogCosmeticPreserve.isLeatherDyeCraft(matrix, vanilla));
        assertFalse(CatalogCosmeticPreserve.isLeatherDyeCraft(
                new ItemStack[] {armor, armor, null, null, null, null, null, null, null}, vanilla),
                "同種2点は修理であり染色ではない");
    }

    @Test
    void applyColorOntoKeepsRollQualityCatalogAndTakesVanillaColor() {
        ItemStack armor = catalogLeather(Color.WHITE);
        ItemStack vanilla = new ItemStack(Material.LEATHER_CHESTPLATE);
        LeatherArmorMeta dyed = (LeatherArmorMeta) vanilla.getItemMeta();
        dyed.setColor(Color.RED);
        vanilla.setItemMeta(dyed);

        ItemStack preserved = CatalogCosmeticPreserve.applyColorOnto(armor, vanilla);

        LeatherArmorMeta meta = (LeatherArmorMeta) preserved.getItemMeta();
        assertEquals(Color.RED, meta.getColor());
        assertEquals(9001, meta.getCustomModelData());
        ItemData data = ItemData.of(meta);
        assertEquals(ROLL, data.rollSeed().orElseThrow());
        assertEquals(11, data.quality());
        assertEquals("light_leather_chest", data.catalogId().orElseThrow());
        assertEquals(BindType.SOULBOUND, data.bindType().orElseThrow());
        assertNotEquals(ROLL, ItemData.of(vanilla.getItemMeta()).rollSeed().orElse(0L),
                "バニラ結果をそのまま返すと rollSeed が落ちる");
    }

    @Test
    void applyTrimOntoKeepsIdentityAndCopiesTrim() {
        ItemStack armor = catalogLeather(Color.BLUE);
        ItemStack vanilla = new ItemStack(Material.LEATHER_CHESTPLATE);
        ArmorMeta vanillaMeta = (ArmorMeta) vanilla.getItemMeta();
        vanillaMeta.setTrim(new ArmorTrim(TrimMaterial.IRON, TrimPattern.SENTRY));
        vanilla.setItemMeta(vanillaMeta);

        ItemStack preserved = CatalogCosmeticPreserve.applyTrimOnto(armor, vanilla);

        ItemData data = ItemData.of(preserved.getItemMeta());
        assertEquals(ROLL, data.rollSeed().orElseThrow());
        assertEquals("light_leather_chest", data.catalogId().orElseThrow());
        ArmorMeta preservedMeta = (ArmorMeta) preserved.getItemMeta();
        assertTrue(preservedMeta.hasTrim());
        assertEquals(TrimPattern.SENTRY, preservedMeta.getTrim().getPattern());
        assertEquals(Color.BLUE, ((LeatherArmorMeta) preservedMeta).getColor(),
                "trim 転写でカタログの染色色を落としてはいけない");
    }

    @Test
    void emptyVanillaTrimResultIsNotReplacedWithAClone() {
        ItemStack armor = catalogLeather(Color.WHITE);
        assertEquals(null, CatalogCosmeticPreserve.applyTrimOnto(armor, null));
    }
}
