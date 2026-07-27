package com.trinityforge.stats;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure material -> slot-category classification (item 8: no double-dip of armor stats). */
class EquipmentSlotResolverTest {

    @Test
    void swordResolvesToMainhand() {
        assertEquals(EquipmentSlotResolver.Category.MAINHAND,
                EquipmentSlotResolver.resolve(Material.DIAMOND_SWORD));
    }

    @Test
    void toolVariantsAcrossTiersAllResolveToMainhand() {
        assertEquals(EquipmentSlotResolver.Category.MAINHAND,
                EquipmentSlotResolver.resolve(Material.IRON_PICKAXE));
        assertEquals(EquipmentSlotResolver.Category.MAINHAND,
                EquipmentSlotResolver.resolve(Material.WOODEN_AXE));
        assertEquals(EquipmentSlotResolver.Category.MAINHAND,
                EquipmentSlotResolver.resolve(Material.NETHERITE_SHOVEL));
        assertEquals(EquipmentSlotResolver.Category.MAINHAND,
                EquipmentSlotResolver.resolve(Material.STONE_HOE));
    }

    @Test
    void rangedAndSpecialWeaponsResolveToMainhand() {
        assertEquals(EquipmentSlotResolver.Category.MAINHAND, EquipmentSlotResolver.resolve(Material.BOW));
        assertEquals(EquipmentSlotResolver.Category.MAINHAND, EquipmentSlotResolver.resolve(Material.CROSSBOW));
        assertEquals(EquipmentSlotResolver.Category.MAINHAND, EquipmentSlotResolver.resolve(Material.TRIDENT));
        assertEquals(EquipmentSlotResolver.Category.MAINHAND, EquipmentSlotResolver.resolve(Material.MACE));
    }

    @Test
    void spearsAreMainhandWeapons() {
        assertEquals(EquipmentSlotResolver.Category.MAINHAND,
                EquipmentSlotResolver.resolve(Material.WOODEN_SPEAR));
        assertEquals(EquipmentSlotResolver.Category.MAINHAND,
                EquipmentSlotResolver.resolve(Material.NETHERITE_SPEAR));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_WEAPON),
                EquipmentSlotResolver.statCategories(Material.WOODEN_SPEAR));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_WEAPON),
                EquipmentSlotResolver.statCategories(Material.NETHERITE_SPEAR));
    }

    @Test
    void helmetResolvesToHead() {
        assertEquals(EquipmentSlotResolver.Category.HEAD,
                EquipmentSlotResolver.resolve(Material.DIAMOND_HELMET));
    }

    @Test
    void turtleHelmetResolvesToHead() {
        assertEquals(EquipmentSlotResolver.Category.HEAD,
                EquipmentSlotResolver.resolve(Material.TURTLE_HELMET));
    }

    @Test
    void chestplateResolvesToChest() {
        assertEquals(EquipmentSlotResolver.Category.CHEST,
                EquipmentSlotResolver.resolve(Material.IRON_CHESTPLATE));
    }

    @Test
    void elytraResolvesToChest() {
        assertEquals(EquipmentSlotResolver.Category.CHEST, EquipmentSlotResolver.resolve(Material.ELYTRA));
    }

    @Test
    void leggingsResolveToLegs() {
        assertEquals(EquipmentSlotResolver.Category.LEGS,
                EquipmentSlotResolver.resolve(Material.GOLDEN_LEGGINGS));
    }

    @Test
    void bootsResolveToFeet() {
        assertEquals(EquipmentSlotResolver.Category.FEET,
                EquipmentSlotResolver.resolve(Material.CHAINMAIL_BOOTS));
    }

    @Test
    void unrecognizedMaterialFallsBackToAny() {
        assertEquals(EquipmentSlotResolver.Category.ANY, EquipmentSlotResolver.resolve(Material.DIRT));
        assertEquals(EquipmentSlotResolver.Category.ANY, EquipmentSlotResolver.resolve(Material.STICK));
    }

    @Test
    void horseArmorIsNotConfusedWithPlayerArmorSlots() {
        // Named "*_HORSE_ARMOR", not "*_CHESTPLATE" etc, and cannot be worn by a player anyway.
        assertEquals(EquipmentSlotResolver.Category.ANY,
                EquipmentSlotResolver.resolve(Material.IRON_HORSE_ARMOR));
    }

    @Test
    void shieldIsNotForcedIntoMainhand() {
        // Shields are conventionally off-hand; leaving them at ANY preserves prior behaviour rather
        // than asserting a debatable slot.
        assertEquals(EquipmentSlotResolver.Category.ANY, EquipmentSlotResolver.resolve(Material.SHIELD));
    }

    @Test
    void rejectsNullMaterial() {
        assertThrows(NullPointerException.class, () -> EquipmentSlotResolver.resolve(null));
    }

    // --- I7 stat-category (applies-to filtering) ---

    @Test
    void combatWeaponsMapToWeaponOnly() {
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_WEAPON),
                EquipmentSlotResolver.statCategories(Material.DIAMOND_SWORD));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_WEAPON),
                EquipmentSlotResolver.statCategories(Material.BOW));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_WEAPON),
                EquipmentSlotResolver.statCategories(Material.CROSSBOW));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_WEAPON),
                EquipmentSlotResolver.statCategories(Material.TRIDENT));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_WEAPON),
                EquipmentSlotResolver.statCategories(Material.MACE));
    }

    @Test
    void productionToolsMapToToolOnly() {
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_TOOL),
                EquipmentSlotResolver.statCategories(Material.IRON_PICKAXE));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_TOOL),
                EquipmentSlotResolver.statCategories(Material.NETHERITE_SHOVEL));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_TOOL),
                EquipmentSlotResolver.statCategories(Material.STONE_HOE));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_TOOL),
                EquipmentSlotResolver.statCategories(Material.FISHING_ROD));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_TOOL),
                EquipmentSlotResolver.statCategories(Material.SHEARS));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_TOOL),
                EquipmentSlotResolver.statCategories(Material.FLINT_AND_STEEL));
    }

    @Test
    void axeIsBothWeaponAndTool() {
        // Dual-classification (DESIGN 2026-07-15): an axe rolls combat AND mining stats.
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_WEAPON, EquipmentSlotResolver.CATEGORY_TOOL),
                EquipmentSlotResolver.statCategories(Material.WOODEN_AXE));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_WEAPON, EquipmentSlotResolver.CATEGORY_TOOL),
                EquipmentSlotResolver.statCategories(Material.NETHERITE_AXE));
    }

    @Test
    void armorPiecesMapToArmorStatCategory() {
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_ARMOR),
                EquipmentSlotResolver.statCategories(Material.DIAMOND_HELMET));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_ARMOR),
                EquipmentSlotResolver.statCategories(Material.NETHERITE_CHESTPLATE));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_ARMOR),
                EquipmentSlotResolver.statCategories(Material.IRON_LEGGINGS));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_ARMOR),
                EquipmentSlotResolver.statCategories(Material.GOLDEN_BOOTS));
    }

    @Test
    void uncategorizedMaterialMapsToOtherStatCategory() {
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_OTHER),
                EquipmentSlotResolver.statCategories(Material.STICK));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_OTHER),
                EquipmentSlotResolver.statCategories(Material.SHIELD));
    }
}
