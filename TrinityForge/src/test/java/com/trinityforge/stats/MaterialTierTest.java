package com.trinityforge.stats;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure material -> tier classification (ITEM_ECONOMY_SPEC 5, the vanilla-attributes ladder). */
class MaterialTierTest {

    @Test
    void toolAndWeaponTiersResolveByPrefix() {
        assertEquals(MaterialTier.WOODEN, MaterialTier.of(Material.WOODEN_SWORD));
        assertEquals(MaterialTier.STONE, MaterialTier.of(Material.STONE_PICKAXE));
        assertEquals(MaterialTier.COPPER, MaterialTier.of(Material.COPPER_AXE));
        assertEquals(MaterialTier.IRON, MaterialTier.of(Material.IRON_SHOVEL));
        assertEquals(MaterialTier.GOLDEN, MaterialTier.of(Material.GOLDEN_HOE));
        assertEquals(MaterialTier.DIAMOND, MaterialTier.of(Material.DIAMOND_SWORD));
        assertEquals(MaterialTier.NETHERITE, MaterialTier.of(Material.NETHERITE_PICKAXE));
    }

    @Test
    void armorTiersResolveByPrefix() {
        assertEquals(MaterialTier.LEATHER, MaterialTier.of(Material.LEATHER_BOOTS));
        assertEquals(MaterialTier.CHAINMAIL, MaterialTier.of(Material.CHAINMAIL_CHESTPLATE));
        assertEquals(MaterialTier.IRON, MaterialTier.of(Material.IRON_HELMET));
        assertEquals(MaterialTier.DIAMOND, MaterialTier.of(Material.DIAMOND_LEGGINGS));
        assertEquals(MaterialTier.NETHERITE, MaterialTier.of(Material.NETHERITE_BOOTS));
        assertEquals(MaterialTier.TURTLE, MaterialTier.of(Material.TURTLE_HELMET));
    }

    @Test
    void unPrefixedEquipmentStillGetsATier() {
        assertEquals(MaterialTier.WOODEN, MaterialTier.of(Material.BOW));
        assertEquals(MaterialTier.WOODEN, MaterialTier.of(Material.TRIDENT));
        assertEquals(MaterialTier.WOODEN, MaterialTier.of(Material.FISHING_ROD));
    }

    @Test
    void nonEquipmentResolvesToNone() {
        assertEquals(MaterialTier.NONE, MaterialTier.of(Material.DIRT));
        assertEquals(MaterialTier.NONE, MaterialTier.of(Material.STICK));
        assertFalse(MaterialTier.of(Material.DIRT).isEquipment());
    }

    /**
     * 2026-08-17 回帰 (ユーザー報告「釣りで釣った鉱石がスタックできない」)。
     *
     * <p>接頭辞だけで判定していたため、インゴットやブロック、金リンゴまで「ティア装備」に化けていた。
     * 釣果が装備扱いになると品質スタンプ(固有 rollSeed)が押されて、
     * 1個ずつ別物になりスタックできなくなる。
     */
    @Test
    void materialsThatMerelyShareTheTierPrefixAreNotEquipment() {
        assertEquals(MaterialTier.NONE, MaterialTier.of(Material.IRON_INGOT));
        assertEquals(MaterialTier.NONE, MaterialTier.of(Material.GOLD_INGOT));
        assertEquals(MaterialTier.NONE, MaterialTier.of(Material.COPPER_INGOT));
        assertEquals(MaterialTier.NONE, MaterialTier.of(Material.GOLDEN_APPLE));
        assertEquals(MaterialTier.NONE, MaterialTier.of(Material.DIAMOND_BLOCK));
        assertEquals(MaterialTier.NONE, MaterialTier.of(Material.IRON_NUGGET));
        assertEquals(MaterialTier.NONE, MaterialTier.of(Material.NETHERITE_SCRAP));
        assertEquals(MaterialTier.NONE, MaterialTier.of(Material.LEATHER));
    }

    @Test
    void equipmentTiersReportIsEquipment() {
        assertTrue(MaterialTier.NETHERITE.isEquipment());
        assertTrue(MaterialTier.of(Material.IRON_SWORD).isEquipment());
    }

    @Test
    void configKeyIsLowerCase() {
        assertEquals("netherite", MaterialTier.NETHERITE.configKey());
        assertEquals("leather", MaterialTier.LEATHER.configKey());
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> MaterialTier.of(null));
    }
}
