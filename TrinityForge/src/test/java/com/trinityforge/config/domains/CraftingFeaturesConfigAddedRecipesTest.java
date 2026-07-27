package com.trinityforge.config.domains;

import com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CraftingFeaturesConfig} の {@code added-recipes} パース検証: shaped/shapeless の正常系、
 * result不正エントリのスキップ(他エントリの読み込みは継続)、amount省略時1・method省略時workbench
 * のデフォルト適用。
 */
class CraftingFeaturesConfigAddedRecipesTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CraftingFeaturesConfigAddedRecipesTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static CraftingFeaturesConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "config must parse cleanly");
        return config;
    }

    @Test
    void shapedEntryIsParsedWithDefaultsAndExplicitAmount(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                added-recipes:
                  - result: DIAMOND_BLOCK
                    amount: 1
                    method: workbench
                    type: shaped
                    shape: ["XXX", "XXX", "XXX"]
                    ingredients: { X: DIAMOND }
                """);
        List<AddedRecipe> added = config.addedRecipes();
        assertEquals(1, added.size());
        AddedRecipe entry = added.get(0);
        assertEquals(Material.DIAMOND_BLOCK, entry.result());
        assertEquals(1, entry.amount());
        assertEquals(RecipeSpec.Type.SHAPED, entry.spec().type());
        assertEquals(RecipeSpec.Method.WORKBENCH, entry.spec().method());
        assertEquals(List.of("XXX", "XXX", "XXX"), entry.spec().shape());
        assertEquals(Material.DIAMOND, entry.spec().shapedIngredients().get('X').material());
    }

    @Test
    void shapelessEntryDefaultsAmountToOneAndMethodToWorkbench(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                added-recipes:
                  - result: STICK
                    amount: 4
                    type: shapeless
                    ingredients: [oak_planks, oak_planks]
                """);
        List<AddedRecipe> added = config.addedRecipes();
        assertEquals(1, added.size());
        AddedRecipe entry = added.get(0);
        assertEquals(Material.STICK, entry.result());
        assertEquals(4, entry.amount());
        assertEquals(RecipeSpec.Method.WORKBENCH, entry.spec().method());
        assertEquals(RecipeSpec.Type.SHAPELESS, entry.spec().type());
        assertEquals(2, entry.spec().shapelessIngredients().size());
        assertEquals(Material.OAK_PLANKS, entry.spec().shapelessIngredients().get(0).material());
    }

    @Test
    void amountBelowOneIsClampedToOne(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                added-recipes:
                  - result: STICK
                    amount: 0
                    type: shapeless
                    ingredients: [oak_planks]
                """);
        assertEquals(1, config.addedRecipes().get(0).amount());
    }

    @Test
    void inventoryMethodIsParsed(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                added-recipes:
                  - result: STICK
                    method: inventory
                    type: shapeless
                    ingredients: [oak_planks]
                """);
        assertEquals(RecipeSpec.Method.INVENTORY, config.addedRecipes().get(0).spec().method());
    }

    @Test
    void entryWithMissingResultIsSkippedButOthersAreLoaded(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                added-recipes:
                  - amount: 1
                    type: shapeless
                    ingredients: [oak_planks]
                  - result: STICK
                    type: shapeless
                    ingredients: [oak_planks]
                """);
        List<AddedRecipe> added = config.addedRecipes();
        assertEquals(1, added.size());
        assertEquals(Material.STICK, added.get(0).result());
    }

    @Test
    void entryWithUnknownResultMaterialIsSkippedButOthersAreLoaded(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                added-recipes:
                  - result: NOT_A_REAL_MATERIAL
                    type: shapeless
                    ingredients: [oak_planks]
                  - result: STICK
                    type: shapeless
                    ingredients: [oak_planks]
                """);
        List<AddedRecipe> added = config.addedRecipes();
        assertEquals(1, added.size());
        assertEquals(Material.STICK, added.get(0).result());
    }

    @Test
    void entryWithInvalidIngredientTokenIsSkippedButOthersAreLoaded(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                added-recipes:
                  - result: DIAMOND_BLOCK
                    type: shapeless
                    ingredients: [not_a_real_material]
                  - result: STICK
                    type: shapeless
                    ingredients: [oak_planks]
                """);
        List<AddedRecipe> added = config.addedRecipes();
        assertEquals(1, added.size());
        assertEquals(Material.STICK, added.get(0).result());
    }

    @Test
    void noAddedRecipesKeyYieldsEmptyList(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, "");
        assertTrue(config.addedRecipes().isEmpty());
    }
}
