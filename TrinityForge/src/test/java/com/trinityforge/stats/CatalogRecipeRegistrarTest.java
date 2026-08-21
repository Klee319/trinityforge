package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CatalogRecipeRegistrar} turns a catalog entry's {@link RecipeSpec} into a real Bukkit
 * recipe keyed {@code trinityforge:catalog_<id>}, using {@link ItemFactory#createIdentityOnly} for
 * the result item (identity only, no rollSeed) so {@code CraftQualityListener} stamps quality per
 * crafter exactly like any other equipment recipe. {@link ItemAssembler} is mocked out (as in {@link
 * ItemFactoryTest}) since the assembler's own behaviour is unrelated to recipe registration.
 */
class CatalogRecipeRegistrarTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CatalogRecipeRegistrarTest");
            case "saveResource" -> throw new AssertionError("file exists; saveResource must not be called");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static ItemCatalogConfig loadCatalog(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    private static ItemFactory factoryWithMockAssembler() {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenReturn(0);
        return new ItemFactory(assembler);
    }

    /**
     * 出荷の形をそのまま写した {@code added-recipe}: 2×2 のスクラップ 4 個 → 素の銅インゴット 1 個。
     */
    private static com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe scrapToIngot() {
        return new com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe(
                Material.COPPER_INGOT, 1,
                RecipeSpec.shaped(List.of("ii", "ii"),
                        Map.of('i', RecipeIngredient.ofCatalog("copper_ingot_scrap")), 1));
    }

    /**
     * <b>2026-08-21 の実サーバ障害の回帰テスト。</b>
     *
     * <p>{@code added-recipes} は結果になるカタログエントリを持たないので、registrar は
     * {@code template} を {@code null} にして {@code fixedResult} だけを載せる
     * ({@link CatalogRecipeRegistrar.RegisteredRecipe} の javadoc に書かれた契約)。
     * 書き出し側がこれを破って {@code template.material()} を直に呼んでいたため、実サーバで
     * 表の書き出しが<b>丸ごと NPE で落ちた</b>
     * ({@code Cannot invoke "ItemTemplate.material()" because "template" is null})。
     * 落ちるのは 1 件ではなく<b>表そのもの</b>なので、スミス台を含む統合版の補正が全部無効になる。
     *
     * <p>出荷 {@code added-recipes} は 7 件とも {@code custom:} 素材(スクラップ→インゴット)＝
     * <b>補正が最も要るレシピ群</b>。出荷カタログだけを通す {@code ShippedBedrockRecipeTableTest}
     * はこの経路を通らないので緑のまま抜けた。だから「registrar に登録させてから書き出す」
     * 実経路をここで通す。
     */
    @Test
    void anAddedRecipeIsExportedToBedrockInsteadOfBringingTheWholeTableDown(@TempDir File tempDir)
            throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  copper_ingot_scrap:
                    material: COPPER_INGOT
                    custom-model-data: 5001
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler(),
                () -> List.of(scrapToIngot()));

        registrar.registerAll();

        com.trinityforge.bedrock.BedrockRecipeTable.Table table =
                com.trinityforge.bedrock.BedrockRecipeExporter.build(
                        registrar.allRegistered(), registrar.allCompletableSmithing(), catalog);

        assertEquals(1, table.recipes().size(),
                "added-recipes は素材がカスタムなので表に載るはず。skipped=" + table.skipped());
        com.trinityforge.bedrock.BedrockRecipeTable.Recipe recipe = table.recipes().get(0);
        assertEquals(new com.trinityforge.bedrock.BedrockRecipeTable.ItemRef(
                        Material.COPPER_INGOT, null, 1), recipe.result(),
                "結果は fixedResult 由来の素のバニラ品(CMD なし)");
        assertTrue(recipe.slots().stream().allMatch(slot -> slot.items().equals(List.of(
                        com.trinityforge.bedrock.BedrockRecipeTable.ItemRef.of(
                                Material.COPPER_INGOT, 5001)))),
                "素材の CMD を落としたら表を出す意味が無い: " + recipe.slots());
    }

    /**
     * カタログ由来のレシピと混在しても、どちらも落ちないこと。
     *
     * <p>障害の本体は「1 件が例外を投げると<b>表全体</b>が書き出されない」ことなので、
     * 混在させて初めて元の壊れ方を再現できる。
     */
    @Test
    void anAddedRecipeDoesNotTakeCatalogRecipesDownWithIt(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  copper_ingot_scrap:
                    material: COPPER_INGOT
                    custom-model-data: 5001
                  copper_ingot_scrap_9x:
                    material: COPPER_INGOT
                    custom-model-data: 5002
                    recipe:
                      method: workbench
                      type: shaped
                      shape:
                        - iii
                        - iii
                        - iii
                      ingredients:
                        i: custom:copper_ingot_scrap
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler(),
                () -> List.of(scrapToIngot()));

        registrar.registerAll();

        com.trinityforge.bedrock.BedrockRecipeTable.Table table =
                com.trinityforge.bedrock.BedrockRecipeExporter.build(
                        registrar.allRegistered(), registrar.allCompletableSmithing(), catalog);

        assertEquals(2, table.recipes().size(),
                "added-recipes とカタログレシピの両方が載るはず。skipped=" + table.skipped());
    }

    private static int countTrinityForgeCatalogRecipes() {
        int count = 0;
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (recipe instanceof org.bukkit.Keyed keyed
                    && "trinityforge".equals(keyed.getKey().getNamespace())
                    && keyed.getKey().getKey().startsWith("catalog_")) {
                count++;
            }
        }
        return count;
    }

    @Test
    void buildRecipeProducesShapedRecipeMatchingSpec(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shaped
                      shape:
                        - "AAA"
                        - " B "
                        - "   "
                      ingredients:
                        A: DIAMOND
                        B: STICK
                      amount: 2
                """);
        ItemFactory factory = factoryWithMockAssembler();
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(fakePlugin(tempDir), catalog, factory);

        Recipe recipe = registrar.buildRecipe(
                new NamespacedKey("trinityforge", "catalog_blade"), catalog.template("blade").orElseThrow());

        ShapedRecipe shaped = assertInstanceOf(ShapedRecipe.class, recipe);
        assertEquals(List.of("AAA", " B ", "   "), List.of(shaped.getShape()));
        assertEquals(Material.DIAMOND, shaped.getIngredientMap().get('A').getType());
        assertEquals(Material.STICK, shaped.getIngredientMap().get('B').getType());
        assertEquals(2, shaped.getResult().getAmount());
    }

    @Test
    void buildRecipeProducesShapelessRecipeMatchingSpec(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shapeless
                      ingredients: [DIAMOND, STICK]
                """);
        ItemFactory factory = factoryWithMockAssembler();
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(fakePlugin(tempDir), catalog, factory);

        Recipe recipe = registrar.buildRecipe(
                new NamespacedKey("trinityforge", "catalog_blade"), catalog.template("blade").orElseThrow());

        ShapelessRecipe shapeless = assertInstanceOf(ShapelessRecipe.class, recipe);
        assertEquals(List.of(Material.DIAMOND, Material.STICK),
                shapeless.getIngredientList().stream()
                        .map(org.bukkit.inventory.ItemStack::getType)
                        .toList());
    }

    @Test
    void resultItemCarriesTemplateIdentityButNoRollSeed(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  blade:
                    material: DIAMOND_SWORD
                    display-name: "Blade"
                    recipe:
                      type: shapeless
                      ingredients: [STICK]
                """);
        ItemFactory factory = factoryWithMockAssembler();
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(fakePlugin(tempDir), catalog, factory);

        Recipe recipe = registrar.buildRecipe(
                new NamespacedKey("trinityforge", "catalog_blade"), catalog.template("blade").orElseThrow());

        var result = recipe.getResult();
        var data = com.trinityforge.pdc.ItemData.of(result.getItemMeta());
        assertEquals("blade", data.catalogId().orElseThrow());
        assertTrue(data.rollSeed().isEmpty(),
                "recipe result must have no rollSeed so CraftQualityListener stamps quality per crafter");
    }

    @Test
    void registerAllRegistersOnlyEntriesWithARecipe(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  with_recipe:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shapeless
                      ingredients: [STICK]
                  without_recipe:
                    material: BOW
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        assertEquals(1, countTrinityForgeCatalogRecipes());
        assertNull(Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_without_recipe")));
    }

    @Test
    void registerAllIsIdempotentAcrossReload(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  with_recipe:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shapeless
                      ingredients: [STICK]
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();
        registrar.registerAll();
        registrar.registerAll();

        // Re-running registerAll() (simulating repeated /trinityforge reload) must not accumulate
        // duplicate or leaked recipe registrations under the same key.
        assertEquals(1, countTrinityForgeCatalogRecipes());
    }

    @Test
    void registerAllRemovesARecipeDroppedFromTheCatalogOnReload(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), """
                items:
                  with_recipe:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shapeless
                      ingredients: [STICK]
                """);
        ItemCatalogConfig catalog = new ItemCatalogConfig();
        catalog.load(fakePlugin(tempDir));
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());
        registrar.registerAll();
        assertEquals(1, countTrinityForgeCatalogRecipes());

        // Simulate a reload that removed the recipe: section entirely.
        Files.writeString(file.toPath(), """
                items:
                  with_recipe:
                    material: DIAMOND_SWORD
                """);
        catalog.load(fakePlugin(tempDir));
        registrar.registerAll();

        assertFalse(Bukkit.recipeIterator().hasNext() && countTrinityForgeCatalogRecipes() > 0);
        assertEquals(0, countTrinityForgeCatalogRecipes());
    }

    @Test
    void listIngredientRegistersMaterialChoiceWithAllMembers(@TempDir File tempDir) throws IOException {
        MaterialLists.update(
                java.util.Map.of("planks", java.util.Set.of(Material.OAK_PLANKS, Material.SPRUCE_PLANKS)),
                java.util.Map.of());
        try {
            ItemCatalogConfig catalog = loadCatalog(tempDir, """
                    items:
                      club:
                        material: DIAMOND_SWORD
                        recipe:
                          type: shaped
                          shape:
                            - " i "
                            - " s "
                            - "   "
                          ingredients:
                            i: list:planks
                            s: STICK
                    """);
            CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                    fakePlugin(tempDir), catalog, factoryWithMockAssembler());

            registrar.registerAll();

            Recipe recipe = Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_club"));
            ShapedRecipe shaped = assertInstanceOf(ShapedRecipe.class, recipe);
            var choice = assertInstanceOf(org.bukkit.inventory.RecipeChoice.MaterialChoice.class,
                    shaped.getChoiceMap().get('i'));
            assertEquals(java.util.Set.of(Material.OAK_PLANKS, Material.SPRUCE_PLANKS),
                    java.util.Set.copyOf(choice.getChoices()));
        } finally {
            MaterialLists.update(java.util.Map.of(), java.util.Map.of());
        }
    }

    @Test
    void unknownListIngredientSkipsOnlyThatRecipe(@TempDir File tempDir) throws IOException {
        // MaterialLists は空 (list:nope は未定義) — 参照レシピだけスキップされ、他は登録される。
        MaterialLists.update(java.util.Map.of(), java.util.Map.of());
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  broken:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shapeless
                      ingredients: [list:nope]
                  fine:
                    material: IRON_SWORD
                    recipe:
                      type: shapeless
                      ingredients: [STICK]
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        assertEquals(1, countTrinityForgeCatalogRecipes());
        assertNull(Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_broken")));
        assertTrue(Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_fine")) != null);
    }

    // ------------------------------------------------------------------
    // D2: inventory method
    // ------------------------------------------------------------------

    @Test
    void registerAllRegistersInventoryMethodRecipesToo(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  mini_ingot:
                    material: IRON_NUGGET
                    recipe:
                      method: inventory
                      type: shaped
                      shape: ["AA", "AA"]
                      ingredients:
                        A: IRON_INGOT
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        assertEquals(1, countTrinityForgeCatalogRecipes());
        Recipe recipe = Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_mini_ingot"));
        assertInstanceOf(ShapedRecipe.class, recipe);
    }

    // ------------------------------------------------------------------
    // D3: reversible auto-generated decompression recipe
    // ------------------------------------------------------------------

    @Test
    void reversibleRecipeRegistersADecompressionRecipeWithMaterialResult(@TempDir File tempDir)
            throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  compressed_iron:
                    material: IRON_BLOCK
                    recipe:
                      type: shaped
                      shape: ["AAA", "AAA", "AAA"]
                      ingredients:
                        A: IRON_INGOT
                      reversible: true
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        Recipe forward = Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_compressed_iron"));
        assertInstanceOf(ShapedRecipe.class, forward);
        Recipe reverse = Bukkit.getRecipe(
                new NamespacedKey("trinityforge", "catalog_compressed_iron_decompress"));
        ShapelessRecipe shapeless = assertInstanceOf(ShapelessRecipe.class, reverse);
        assertEquals(1, shapeless.getIngredientList().size());
        assertEquals(Material.IRON_BLOCK, shapeless.getIngredientList().get(0).getType());
        assertEquals(Material.IRON_INGOT, shapeless.getResult().getType());
        assertEquals(9, shapeless.getResult().getAmount());
    }

    @Test
    void reversibleRecipeDecompressesToCustomCatalogItem(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  ember_core:
                    material: MAGMA_CREAM
                  ember_core_block:
                    material: IRON_BLOCK
                    recipe:
                      type: shapeless
                      ingredients: [custom:ember_core, custom:ember_core, custom:ember_core, custom:ember_core]
                      reversible: true
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        Recipe reverse = Bukkit.getRecipe(
                new NamespacedKey("trinityforge", "catalog_ember_core_block_decompress"));
        ShapelessRecipe shapeless = assertInstanceOf(ShapelessRecipe.class, reverse);
        assertEquals(Material.MAGMA_CREAM, shapeless.getResult().getType());
        assertEquals(4, shapeless.getResult().getAmount());
        assertEquals("ember_core",
                com.trinityforge.pdc.ItemData.of(shapeless.getResult().getItemMeta()).catalogId().orElseThrow());
    }

    @Test
    void nonReversibleRecipeDoesNotRegisterADecompressionRecipe(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shapeless
                      ingredients: [STICK]
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        assertNull(Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_blade_decompress")));
    }

    @Test
    void reverseRecipeIsRemovedOnReregisterAll(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  compressed_iron:
                    material: IRON_BLOCK
                    recipe:
                      type: shaped
                      shape: ["AAA", "AAA", "AAA"]
                      ingredients:
                        A: IRON_INGOT
                      reversible: true
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();
        registrar.registerAll();
        registrar.registerAll();

        // registerAll() must not leak/duplicate the reverse recipe registration either.
        int reverseCount = 0;
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (recipe instanceof org.bukkit.Keyed keyed
                    && "trinityforge".equals(keyed.getKey().getNamespace())
                    && keyed.getKey().getKey().equals("catalog_compressed_iron_decompress")) {
                reverseCount++;
            }
        }
        assertEquals(1, reverseCount);
    }

    // ------------------------------------------------------------------
    // added-recipes (progression/crafting-features.yml): buildStandaloneRecipe + registerAll wiring
    // ------------------------------------------------------------------

    @Test
    void buildStandaloneRecipeProducesShapedRecipeWithExplicitResult(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, "items: {}\n");
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        java.util.Map<Character, RecipeIngredient> ingredients = new java.util.LinkedHashMap<>();
        ingredients.put('A', RecipeIngredient.ofMaterial(Material.DIAMOND));
        RecipeSpec spec = RecipeSpec.shaped(
                RecipeSpec.Method.WORKBENCH, List.of("AAA", "AAA", "AAA"), ingredients, 1);
        org.bukkit.inventory.ItemStack result = new org.bukkit.inventory.ItemStack(Material.DIAMOND_BLOCK, 1);

        Recipe recipe = registrar.buildStandaloneRecipe(
                new NamespacedKey("trinityforge", "added_1"), result, spec);

        ShapedRecipe shaped = assertInstanceOf(ShapedRecipe.class, recipe);
        assertEquals(Material.DIAMOND_BLOCK, shaped.getResult().getType());
        assertEquals(1, shaped.getResult().getAmount());
        assertEquals(List.of("AAA", "AAA", "AAA"), List.of(shaped.getShape()));
        assertEquals(Material.DIAMOND, shaped.getIngredientMap().get('A').getType());
    }

    @Test
    void buildStandaloneRecipeProducesShapelessRecipeWithExplicitResult(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, "items: {}\n");
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        RecipeSpec spec = RecipeSpec.shapeless(RecipeSpec.Method.WORKBENCH,
                List.of(RecipeIngredient.ofMaterial(Material.OAK_PLANKS),
                        RecipeIngredient.ofMaterial(Material.OAK_PLANKS)),
                4);
        org.bukkit.inventory.ItemStack result = new org.bukkit.inventory.ItemStack(Material.STICK, 4);

        Recipe recipe = registrar.buildStandaloneRecipe(
                new NamespacedKey("trinityforge", "added_1"), result, spec);

        ShapelessRecipe shapeless = assertInstanceOf(ShapelessRecipe.class, recipe);
        assertEquals(Material.STICK, shapeless.getResult().getType());
        assertEquals(4, shapeless.getResult().getAmount());
        assertEquals(List.of(Material.OAK_PLANKS, Material.OAK_PLANKS),
                shapeless.getIngredientList().stream()
                        .map(org.bukkit.inventory.ItemStack::getType)
                        .toList());
    }

    @Test
    void registerAllRegistersAddedRecipesFromSupplierWithDistinctKeys(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, "items: {}\n");

        java.util.Map<Character, RecipeIngredient> ingredients = new java.util.LinkedHashMap<>();
        ingredients.put('A', RecipeIngredient.ofMaterial(Material.DIAMOND));
        RecipeSpec shapedSpec = RecipeSpec.shaped(
                RecipeSpec.Method.WORKBENCH, List.of("AAA", "AAA", "AAA"), ingredients, 1);
        RecipeSpec shapelessSpec = RecipeSpec.shapeless(RecipeSpec.Method.WORKBENCH,
                List.of(RecipeIngredient.ofMaterial(Material.OAK_PLANKS),
                        RecipeIngredient.ofMaterial(Material.OAK_PLANKS)),
                4);
        List<com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe> added = List.of(
                new com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe(
                        Material.DIAMOND_BLOCK, 1, shapedSpec),
                new com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe(
                        Material.STICK, 4, shapelessSpec));

        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler(), () -> added);

        registrar.registerAll();

        Recipe first = Bukkit.getRecipe(new NamespacedKey("trinityforge", "added_1"));
        Recipe second = Bukkit.getRecipe(new NamespacedKey("trinityforge", "added_2"));
        assertInstanceOf(ShapedRecipe.class, first);
        assertEquals(Material.DIAMOND_BLOCK, first.getResult().getType());
        assertInstanceOf(ShapelessRecipe.class, second);
        assertEquals(Material.STICK, second.getResult().getType());
        assertEquals(4, second.getResult().getAmount());
    }

    @Test
    void registerAllWithoutAddedRecipesSupplierRegistersNoAddedRecipes(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, "items: {}\n");
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        assertNull(Bukkit.getRecipe(new NamespacedKey("trinityforge", "added_1")));
    }

    @Test
    void registerAllIsIdempotentForAddedRecipesAcrossReload(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, "items: {}\n");
        RecipeSpec spec = RecipeSpec.shapeless(
                RecipeSpec.Method.WORKBENCH, List.of(RecipeIngredient.ofMaterial(Material.STICK)), 1);
        List<com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe> added = List.of(
                new com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe(
                        Material.DIAMOND_BLOCK, 1, spec));
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler(), () -> added);

        registrar.registerAll();
        registrar.registerAll();
        registrar.registerAll();

        int count = 0;
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (recipe instanceof org.bukkit.Keyed keyed
                    && "trinityforge".equals(keyed.getKey().getNamespace())
                    && keyed.getKey().getKey().equals("added_1")) {
                count++;
            }
        }
        assertEquals(1, count);
    }
}
