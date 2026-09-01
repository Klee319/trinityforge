package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.DisassemblyRule;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.stats.MaterialLists;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisassemblyListenerTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void materialListInputCountsEveryConfiguredEquivalentVanillaMaterial() {
        MaterialLists.update(Map.of("planks", java.util.Set.of(Material.OAK_PLANKS, Material.SPRUCE_PLANKS)), Map.of());

        assertTrue(DisassemblyListener.materialMatchesInput(Material.OAK_PLANKS, "list:planks"));
        assertTrue(DisassemblyListener.materialMatchesInput(Material.SPRUCE_PLANKS, "list:planks"));
        assertTrue(!DisassemblyListener.materialMatchesInput(Material.STICK, "list:planks"));
    }

    @Test
    void craftingCountIgnoresNonNetheriteSmithingSoTrimRecipesCannotInflateScrap() {
        org.bukkit.inventory.SmithingTransformRecipe trim = org.mockito.Mockito.mock(
                org.bukkit.inventory.SmithingTransformRecipe.class);
        org.mockito.Mockito.when(trim.getResult()).thenReturn(new org.bukkit.inventory.ItemStack(
                Material.IRON_CHESTPLATE));
        org.bukkit.inventory.RecipeChoice addition = org.mockito.Mockito.mock(
                org.bukkit.inventory.RecipeChoice.class);
        org.mockito.Mockito.when(addition.test(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        org.mockito.Mockito.when(trim.getAddition()).thenReturn(addition);

        assertEquals(0, DisassemblyListener.craftingOrNetheriteUpgradeCount(trim, "IRON_INGOT"),
                "防具装飾の鍛冶を足すと鉄チェストの素材数が作業台8から膨らむ");
    }

    @Test
    void netheriteTrimIsNotCountedAsAnUpgradeEvenThoughTheResultIsNetherite() {
        org.bukkit.inventory.SmithingTransformRecipe trim = org.mockito.Mockito.mock(
                org.bukkit.inventory.SmithingTransformRecipe.class);
        org.mockito.Mockito.when(trim.getResult()).thenReturn(new org.bukkit.inventory.ItemStack(
                Material.NETHERITE_CHESTPLATE));
        org.bukkit.inventory.RecipeChoice addition = org.mockito.Mockito.mock(
                org.bukkit.inventory.RecipeChoice.class);
        org.mockito.Mockito.when(addition.test(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        org.mockito.Mockito.when(trim.getAddition()).thenReturn(addition);
        org.bukkit.inventory.RecipeChoice base = org.mockito.Mockito.mock(
                org.bukkit.inventory.RecipeChoice.class);
        org.mockito.Mockito.when(base.test(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        org.mockito.Mockito.when(trim.getBase()).thenReturn(base);

        assertEquals(0, DisassemblyListener.craftingOrNetheriteUpgradeCount(trim, "NETHERITE_INGOT"),
                "ネザライト装飾も成果物が NETHERITE_ なので、基材一致で弾かないと強化と区別できない");
    }

    @Test
    void netheriteUpgradeStillCountsOneAddition() {
        org.bukkit.inventory.SmithingTransformRecipe upgrade = org.mockito.Mockito.mock(
                org.bukkit.inventory.SmithingTransformRecipe.class);
        org.mockito.Mockito.when(upgrade.getResult()).thenReturn(new org.bukkit.inventory.ItemStack(
                Material.NETHERITE_CHESTPLATE));
        org.bukkit.inventory.RecipeChoice addition = org.mockito.Mockito.mock(
                org.bukkit.inventory.RecipeChoice.class);
        org.mockito.Mockito.when(addition.test(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        org.mockito.Mockito.when(upgrade.getAddition()).thenReturn(addition);
        org.bukkit.inventory.RecipeChoice base = org.mockito.Mockito.mock(
                org.bukkit.inventory.RecipeChoice.class);
        org.mockito.Mockito.when(base.test(org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        org.mockito.Mockito.when(upgrade.getBase()).thenReturn(base);

        assertEquals(1, DisassemblyListener.craftingOrNetheriteUpgradeCount(upgrade, "NETHERITE_INGOT"),
                "ダイヤモンド→ネザライト強化は addition 1個を数える");
    }

    @Test
    void shapedCountWalksEverySlotNotUniqueIngredientKeys() {
        org.bukkit.inventory.ShapedRecipe chest = new org.bukkit.inventory.ShapedRecipe(
                org.bukkit.NamespacedKey.minecraft("leather_chestplate"),
                new org.bukkit.inventory.ItemStack(Material.LEATHER_CHESTPLATE));
        chest.shape("X X", "XXX", "XXX");
        chest.setIngredient('X', Material.LEATHER);

        assertEquals(8, DisassemblyListener.craftingOrNetheriteUpgradeCount(chest, "LEATHER"),
                "getIngredientMap はキーXが1件なので、マスを歩かないと革チェストが1になり Lv3 で0個戻る");
        assertEquals(8L, DisassemblyListener.returnAmount(8, 60, 2.0, 0.0),
                "Lv3 60% × 倍率2 の革チェストは8。unique-key 合算5だと6になる");
    }

    @Test
    void vanillaLeatherChestIgnoresCatalogRecipesThatShareTheMaterial() {
        org.bukkit.inventory.ItemStack vanilla = new org.bukkit.inventory.ItemStack(Material.LEATHER_CHESTPLATE);
        org.bukkit.inventory.ShapedRecipe vanillaRecipe = new org.bukkit.inventory.ShapedRecipe(
                org.bukkit.NamespacedKey.minecraft("leather_chestplate"), vanilla.clone());
        vanillaRecipe.shape("X X", "XXX", "XXX");
        vanillaRecipe.setIngredient('X', Material.LEATHER);

        org.bukkit.inventory.ItemStack catalogResult = new org.bukkit.inventory.ItemStack(Material.LEATHER_CHESTPLATE);
        catalogResult.editMeta(meta -> meta.setCustomModelData(200125));
        org.bukkit.inventory.ShapedRecipe catalog = new org.bukkit.inventory.ShapedRecipe(
                new org.bukkit.NamespacedKey("trinityforge", "bone_guard_chestplate"), catalogResult);
        catalog.shape("Y Y", "XXX", "XXX");
        catalog.setIngredient('X', Material.BONE);
        catalog.setIngredient('Y', Material.LEATHER);

        assertEquals(8.0, DisassemblyListener.lowestMatchingIngredientCount(
                vanilla, "LEATHER", java.util.List.of(vanillaRecipe, catalog)),
                "getRecipesFor は material 一致なので、カタログの革2枠を min すると6や2に潰れる");
        assertTrue(DisassemblyListener.isVanillaRecipeForUnstampedItem(vanilla, vanillaRecipe));
        assertTrue(!DisassemblyListener.isVanillaRecipeForUnstampedItem(vanilla, catalog),
                "trinityforge 名前空間＋CMD 付き成果物はバニラ革チェストのレシピではない");
    }

    @Test
    void dismantleLevelUsesHighestUnlockedTierInsteadOfSummingCumulativeNodes() {
        DedicatedEffectsConfig effects = org.mockito.Mockito.mock(DedicatedEffectsConfig.class);
        Player player = org.mockito.Mockito.mock(Player.class);
        org.mockito.Mockito.when(effects.valueMax(player, "dismantle-unlock"))
                .thenReturn(OptionalDouble.of(3.0));
        org.mockito.Mockito.when(effects.valueSum(player, "dismantle-unlock")).thenReturn(6.0);

        assertEquals(3, DisassemblyListener.dismantleLevel(effects, player),
                "C/D/E の絶対tier 1/2/3を足すと6になり、未定義tierの150%へフォールバックしてしまう");
        org.mockito.Mockito.verify(effects, org.mockito.Mockito.never())
                .valueSum(player, "dismantle-unlock");
    }

    @Test
    void scaleByStackMultipliesReturnsByTheDroppedAmount() {
        assertEquals(5, DisassemblyListener.scaleByStack(5, 1));
        assertEquals(15, DisassemblyListener.scaleByStack(5, 3),
                "同じ部位を固めて落とすと Item がマージされ、1着分しか戻らなかった");
        assertEquals(-1, DisassemblyListener.scaleByStack(-1, 8),
                "不正値は倍にせず安全弁のまま返す");
    }

    @Test
    void targetSeriesAcceptsTrailingWildcardAndHasNoImplicitFallback() throws ReflectiveOperationException {
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        Field field = CraftingFeaturesConfig.class.getDeclaredField("disassemblyItems");
        field.setAccessible(true);
        field.set(config, Map.of("bronze_*", List.of(new DisassemblyRule("IRON_INGOT", "COPPER_INGOT", 0.5))));

        assertEquals(1, config.disassemblyRulesFor("bronze_war_axe").size());
        assertTrue(config.disassemblyRulesFor("iron_war_axe").isEmpty(),
                "unconfigured targets must not receive a scrap or material fallback");
    }

    @Test
    void exactRuleWinsOverWildcardAndTheMostSpecificWildcardWins() throws ReflectiveOperationException {
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        Field field = CraftingFeaturesConfig.class.getDeclaredField("disassemblyItems");
        field.setAccessible(true);
        DisassemblyRule broad = new DisassemblyRule("IRON_INGOT", "COBBLESTONE", 1.0);
        DisassemblyRule specific = new DisassemblyRule("IRON_INGOT", "IRON_NUGGET", 1.0);
        DisassemblyRule exact = new DisassemblyRule("IRON_INGOT", "DIAMOND", 1.0);
        field.set(config, Map.of("weapon_*", List.of(broad), "weapon_iron_*", List.of(specific),
                "weapon_iron_sword", List.of(exact)));

        assertEquals("DIAMOND", config.disassemblyRulesFor("weapon_iron_sword").getFirst().output());
        assertEquals("IRON_NUGGET", config.disassemblyRulesFor("weapon_iron_axe").getFirst().output());
    }

    @Test
    void returnAmountUsesTheSpecifiedDoubleFloorAndRejectsUnsafeValues() {
        // floor(floor(8 × 3 × 25 / 100) × 1.5) = floor(6 × 1.5) = 9
        assertEquals(9, DisassemblyListener.returnAmount(8, 3, 25, 1.5));
        assertEquals(0, DisassemblyListener.returnAmount(2.0 / 3.0, 1, 100, 1.0),
                "one item from a three-output recipe must not refund two full ingredients");
        assertEquals(-1, DisassemblyListener.returnAmount(8, 3, 25, Double.POSITIVE_INFINITY));
        assertEquals(-1, DisassemblyListener.returnAmount(8, Integer.MAX_VALUE, Integer.MAX_VALUE, 1.0));
    }

    // ---- T3 (2026-07-25経済連携): disassembly_return_bonus 追加乗算オーバーロード ----
    // 5引数版はpackage-privateで、このテストクラスと同一パッケージのため直接呼べる。

    @Test
    void returnAmountWithBuffAppliesAdditionalMultiplierOnTopOfTheBaseCalculation() {
        // base = floor(floor(8 × 3 × 25 / 100) × 1.5) = 9 (same as the 4-arg case above).
        // buffed = floor(9 × (1 + 0.5)) = floor(13.5) = 13.
        assertEquals(13L, DisassemblyListener.returnAmount(8, 3, 25, 1.5, 0.5));
    }

    @Test
    void returnAmountWithBuffIgnoresNonPositiveOrNonFiniteBuffAndReturnsTheBaseValueUnchanged() {
        long base = DisassemblyListener.returnAmount(8, 3, 25, 1.5);
        assertEquals(base, DisassemblyListener.returnAmount(8, 3, 25, 1.5, 0.0));
        assertEquals(base, DisassemblyListener.returnAmount(8, 3, 25, 1.5, -0.5));
        assertEquals(base, DisassemblyListener.returnAmount(8, 3, 25, 1.5, Double.NaN));
    }

    @Test
    void returnAmountWithBuffKeepsTheSafetyValveWhenTheBaseCalculationAlreadyTripped() {
        // Base calc already trips the -1 safety valve (POSITIVE_INFINITY multiplier); a huge buff
        // must never resurrect a positive return amount out of an already-unsafe base.
        assertEquals(-1L, DisassemblyListener.returnAmount(8, 3, 25, Double.POSITIVE_INFINITY, 999.0));
    }

    @Test
    void returnAmountWithBuffKeepsTheSafetyValveWhenTheBuffedResultOverflows() {
        // A valid, moderate base amount pushed past Integer.MAX_VALUE by an extreme buff must still
        // trip the safety valve (-1), not silently overflow into a bogus positive long.
        assertEquals(-1L, DisassemblyListener.returnAmount(8, 3, 25, 1.5, (double) Integer.MAX_VALUE));
    }
}
