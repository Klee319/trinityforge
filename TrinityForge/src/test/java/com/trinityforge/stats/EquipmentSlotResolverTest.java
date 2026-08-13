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

    // --- レーンC(2026-08-13): 頭スロットに実際に装備できる素材のHEAD分類 ---

    @Test
    void carvedPumpkinResolvesToHead() {
        assertEquals(EquipmentSlotResolver.Category.HEAD,
                EquipmentSlotResolver.resolve(Material.CARVED_PUMPKIN));
    }

    @Test
    void playerAndMobHeadsResolveToHead() {
        assertEquals(EquipmentSlotResolver.Category.HEAD,
                EquipmentSlotResolver.resolve(Material.PLAYER_HEAD));
        assertEquals(EquipmentSlotResolver.Category.HEAD,
                EquipmentSlotResolver.resolve(Material.ZOMBIE_HEAD));
        assertEquals(EquipmentSlotResolver.Category.HEAD,
                EquipmentSlotResolver.resolve(Material.SKELETON_SKULL));
        assertEquals(EquipmentSlotResolver.Category.HEAD,
                EquipmentSlotResolver.resolve(Material.WITHER_SKELETON_SKULL));
        assertEquals(EquipmentSlotResolver.Category.HEAD,
                EquipmentSlotResolver.resolve(Material.CREEPER_HEAD));
        assertEquals(EquipmentSlotResolver.Category.HEAD,
                EquipmentSlotResolver.resolve(Material.DRAGON_HEAD));
        assertEquals(EquipmentSlotResolver.Category.HEAD,
                EquipmentSlotResolver.resolve(Material.PIGLIN_HEAD));
    }

    @Test
    void pistonHeadIsNotConfusedWithPlayerHeadSlot() {
        // 接尾辞判定("_HEAD"で終わる)への回帰を固定する: PISTON_HEADは技術ブロックで頭スロットに装備できない。
        assertEquals(EquipmentSlotResolver.Category.ANY,
                EquipmentSlotResolver.resolve(Material.PISTON_HEAD));
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

    // --- レーンC(2026-08-13)の副作用固定: 頭スロット装備可能素材の追加が statCategories にも波及する ---

    @Test
    void headEquippableCarvedPumpkinAndPlayerHeadMapToArmorStatCategory() {
        // これはレーンCが resolve() へ CARVED_PUMPKIN/PLAYER_HEAD 等をHEAD分類として追加した
        // 意図した副作用であり、避けられない: statCategories() は resolve() の分類結果を丸ごと再利用する
        // 共有関数のため、resolve() 側の変更は自動的にこちらにも伝播する。出荷 item-stats.yml に
        // 該当素材のエントリは無いので現時点の実害はゼロだが、将来ここへエントリを足す人がこの分類変更に
        // 気づけるよう固定する。
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_ARMOR),
                EquipmentSlotResolver.statCategories(Material.CARVED_PUMPKIN));
        assertEquals(Set.of(EquipmentSlotResolver.CATEGORY_ARMOR),
                EquipmentSlotResolver.statCategories(Material.PLAYER_HEAD));
    }
}
