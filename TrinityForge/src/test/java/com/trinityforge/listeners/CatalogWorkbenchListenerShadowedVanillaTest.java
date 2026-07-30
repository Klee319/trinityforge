package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「カタログレシピが同じ盤面のバニラレシピを無言で潰す」問題の回帰ガード (2026-07-28)。
 *
 * <p>{@code custom:}/{@code list:} 素材は {@link CatalogRecipeRegistrar} で MaterialChoice
 * (=材質のみ照合)として登録されるため、素のバニラ素材だけの盤面でも Bukkit がカタログレシピを
 * 選んでしまうことがある。Bukkit は一致レシピを1つしか返さないので、そのとき同じ盤面に一致する
 * バニラレシピは選択肢ごと消える。per-slot 検証がカタログレシピを弾いたあと結果をクリアすると、
 * <b>バニラレシピが結果枠の空白として無言で死ぬ</b>
 * (実サーバでは ArsPaper の {@code plank_scrap} が {@code minecraft:crafting_table} を潰し
 * 「作業台が作れない」として表面化した)。
 */
class CatalogWorkbenchListenerShadowedVanillaTest {

    private CatalogWorkbenchListener listener;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        CatalogRecipeRegistrar registrar = mock(CatalogRecipeRegistrar.class);
        // 走査で当たるレシピはすべて「TF 登録ではない」= 復元候補として採用してよい。
        when(registrar.registered(any())).thenReturn(Optional.empty());
        listener = new CatalogWorkbenchListener(registrar, mock(ItemCatalogConfig.class));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 板材 2×2 → 作業台(バニラ相当)。 */
    private void registerCraftingTableRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey("minecraft", "crafting_table"), new ItemStack(Material.CRAFTING_TABLE));
        recipe.shape("pp", "pp");
        recipe.setIngredient('p', Material.OAK_PLANKS);
        Bukkit.addRecipe(recipe);
    }

    private ItemStack[] grid(Material... materials) {
        ItemStack[] matrix = new ItemStack[materials.length];
        for (int i = 0; i < materials.length; i++) {
            matrix[i] = materials[i] == null ? null : new ItemStack(materials[i]);
        }
        return matrix;
    }

    @Test
    @DisplayName("素のバニラ盤面ならカタログレシピが潰したバニラレシピの結果を復元する")
    void restoresShadowedVanillaResult() {
        registerCraftingTableRecipe();

        ItemStack result = listener.shadowedVanillaResult(
                grid(Material.OAK_PLANKS, Material.OAK_PLANKS,
                        Material.OAK_PLANKS, Material.OAK_PLANKS), 2);

        assertEquals(Material.CRAFTING_TABLE, result == null ? null : result.getType());
    }

    @Test
    @DisplayName("3x3 の作業台でも 2x2 の形をどこに置いても復元する")
    void restoresInsideTheThreeByThreeGrid() {
        registerCraftingTableRecipe();

        ItemStack result = listener.shadowedVanillaResult(
                grid(null, null, null,
                        null, Material.OAK_PLANKS, Material.OAK_PLANKS,
                        null, Material.OAK_PLANKS, Material.OAK_PLANKS), 3);

        assertEquals(Material.CRAFTING_TABLE, result == null ? null : result.getType());
    }

    @Test
    @DisplayName("shapeless のバニラレシピも復元対象")
    void restoresShapelessVanillaResult() {
        ShapelessRecipe recipe = new ShapelessRecipe(
                new NamespacedKey("minecraft", "blaze_powder"), new ItemStack(Material.BLAZE_POWDER, 2));
        recipe.addIngredient(Material.BLAZE_ROD);
        Bukkit.addRecipe(recipe);

        ItemStack result = listener.shadowedVanillaResult(grid(Material.BLAZE_ROD, null, null, null), 2);

        assertEquals(Material.BLAZE_POWDER, result == null ? null : result.getType());
    }

    @Test
    @DisplayName("どのバニラレシピにも合わない盤面は null(=クラフト不可のまま)")
    void unmatchedGridStaysUncraftable() {
        registerCraftingTableRecipe();

        assertNull(listener.shadowedVanillaResult(
                grid(Material.OAK_PLANKS, Material.OAK_PLANKS, Material.OAK_PLANKS, null), 2));
    }

    @Test
    @DisplayName("盤面にCMD付きカタログ品が混ざる場合は復元しない(圧縮ブロックの tier 誤爆を再び開けないため)")
    void catalogItemInGridBlocksTheFallback() {
        registerCraftingTableRecipe();

        ItemStack[] matrix = grid(Material.OAK_PLANKS, Material.OAK_PLANKS,
                Material.OAK_PLANKS, Material.OAK_PLANKS);
        ItemMeta meta = matrix[0].getItemMeta();
        meta.setCustomModelData(999);
        ItemData.of(meta).setCatalogId("plank_scrap");
        matrix[0].setItemMeta(meta);

        assertNull(listener.shadowedVanillaResult(matrix, 2));
    }

    @Test
    @DisplayName("カタログ品を通常のバニラ素材として消費するレシピは結果を消す")
    void catalogItemCannotBeConsumedByAnUnrelatedVanillaRecipe() {
        CatalogRecipeRegistrar registrar = mock(CatalogRecipeRegistrar.class);
        when(registrar.registered(any())).thenReturn(Optional.empty());
        when(registrar.allRegistered()).thenReturn(java.util.List.of());
        listener = new CatalogWorkbenchListener(registrar, mock(ItemCatalogConfig.class));

        ShapelessRecipe vanilla = new ShapelessRecipe(
                new NamespacedKey("minecraft", "glowstone_dust"), new ItemStack(Material.GLOWSTONE_DUST, 4));
        vanilla.addIngredient(Material.GLOWSTONE);
        ItemStack halo = new ItemStack(Material.GLOWSTONE);
        ItemMeta meta = halo.getItemMeta();
        meta.setCustomModelData(84);
        ItemData.of(meta).setCatalogId("novus_criculus_luminis");
        halo.setItemMeta(meta);

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getMatrix()).thenReturn(new ItemStack[] {halo, null, null, null});
        PrepareItemCraftEvent event = mock(PrepareItemCraftEvent.class);
        when(event.getRecipe()).thenReturn(vanilla);
        when(event.getInventory()).thenReturn(inventory);

        listener.onPrepareCraft(event);

        verify(inventory).setResult(null);
    }
}
