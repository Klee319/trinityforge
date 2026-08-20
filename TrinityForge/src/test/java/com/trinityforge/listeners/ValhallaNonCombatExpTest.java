package com.trinityforge.listeners;

import com.trinityforge.progression.catalog.SkillCatalogEntry;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Valhalla の非戦闘 EXP 表を producer が取りこぼさず、行動・素材・段階差をそのまま
 * EXP に反映するための純粋関数テスト。
 */
class ValhallaNonCombatExpTest {

    @Test
    void enchantingUsesConfiguredEnchantmentLevelTypeAndItemMultipliers() {
        SkillCatalogEntry entry = entry(
                Map.of(
                        "exp_gain.enchantment_base.sharpness", 10.0,
                        "exp_gain.enchantment_level_multiplier.3", 4.0,
                        "exp_gain.enchantment_type_multiplier.DIAMOND", 2.0,
                        "exp_gain.enchantment_item_multiplier.SWORD", 3.0),
                Map.of("enchant.level_cost_multiplier", 0.5));

        double exp = NativeSkillExperienceListener.enchantingExp(
                entry, Map.of("sharpness", 3), Material.DIAMOND_SWORD, 3);

        assertEquals(241.5, exp, 0.0,
                "10 base × level 4 × diamond 2 × sword 3 + 3 spent levels × 0.5");
    }

    @Test
    void enchantingUnknownEnchantStillUsesConfiguredSpentLevelConversion() {
        SkillCatalogEntry entry = entry(Map.of(), Map.of("enchant.level_cost_multiplier", 2.0));

        assertEquals(6.0, NativeSkillExperienceListener.enchantingExp(
                entry, Map.of("modded_unknown", 5), Material.BOOK, 3), 0.0);
    }

    @Test
    void dropActionCountsConfiguredValuePerActuallyDroppedItem() {
        SkillCatalogEntry entry = entry(
                Map.of("entity_drops.BEEF", 60.0, "entity_drops.LEATHER", 40.0), Map.of());

        double exp = NativeSkillExperienceListener.dropActionExp(entry, "entity_drops", List.of(
                stack(Material.BEEF, 2),
                stack(Material.LEATHER, 1),
                stack(Material.ROTTEN_FLESH, 64)));

        assertEquals(160.0, exp, 0.0);
    }

    @Test
    void woodStrippingUsesConfiguredResultMaterialInsteadOfFlatAmount() {
        SkillCatalogEntry entry = entry(Map.of(
                "woodcutting_strip.STRIPPED_OAK_LOG", 20.0,
                "woodcutting_strip.STRIPPED_CRIMSON_HYPHAE", 40.0), Map.of());

        assertEquals(20.0,
                NativeSkillExperienceListener.woodStripExp(entry, Material.OAK_LOG), 0.0);
        assertEquals(40.0,
                NativeSkillExperienceListener.woodStripExp(entry, Material.CRIMSON_HYPHAE), 0.0);
        assertEquals(0.0,
                NativeSkillExperienceListener.woodStripExp(entry, Material.STONE), 0.0);
    }

    @Test
    void alchemyUsesConfiguredRecipeStageInsteadOfOneFlatBrewAmount() {
        SkillCatalogEntry entry = entry(Map.of(
                "brew_result.AWKWARD", 150.0,
                "brew_result.TURTLE_MASTER", 1500.0,
                "brew_ingredient.REDSTONE", 150.0), Map.of("alchemy.brew", 150.0));

        assertEquals(1500.0, NativeSkillExperienceListener.alchemyBrewExp(
                entry, null, List.of("TURTLE_MASTER")), 0.0);
        assertEquals(150.0, NativeSkillExperienceListener.alchemyBrewExp(
                entry, "REDSTONE", List.of("LONG_TURTLE_MASTER")), 0.0,
                "extension ingredient overrides the base potion reward like Valhalla's recipe reward");
        assertEquals(150.0, NativeSkillExperienceListener.alchemyBrewExp(
                entry, null, List.of("UNKNOWN_CUSTOM_POTION")), 0.0);
    }

    @Test
    void boneMealClickOnMatureBerryBushIsNotMiscreditedAsHarvest() {
        Block block = mock(Block.class);
        Ageable berries = mock(Ageable.class);
        when(block.getType()).thenReturn(Material.SWEET_BERRY_BUSH);
        when(block.getBlockData()).thenReturn(berries);
        when(berries.getAge()).thenReturn(3);
        ItemStack boneMeal = stack(Material.BONE_MEAL, 1);

        assertEquals(false,
                NativeSkillExperienceListener.isHarvestableFarmingInteraction(block, boneMeal));
        assertEquals(true,
                NativeSkillExperienceListener.isHarvestableFarmingInteraction(block, null));
    }

    private static SkillCatalogEntry entry(Map<String, Double> actionExp, Map<String, Double> rates) {
        return new SkillCatalogEntry("TEST", 100, "1", level -> 1L, actionExp, rates);
    }

    private static ItemStack stack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        return stack;
    }
}
