package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.ExternalItemRegistry;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.MaterialLists;
import com.trinityforge.stats.RecipeIngredient;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Grid-matching unit tests for {@link CatalogWorkbenchListener}. Focus: the shapeless matcher must
 * solve a real bipartite matching — with {@code list:} ingredients whose accepted sets overlap a
 * plain-material ingredient, a greedy first-fit assignment produces false negatives depending on
 * slot order.
 */
class CatalogWorkbenchListenerTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        MaterialLists.update(
                Map.of("planks", Set.of(Material.OAK_PLANKS, Material.SPRUCE_PLANKS)),
                Map.of());
    }

    @AfterEach
    void tearDown() {
        MaterialLists.update(Map.of(), Map.of());
        ExternalItemRegistry.updateExternalPlugin("arspaper", Map.of());
        MockBukkit.unmock();
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CatalogWorkbenchListenerTest");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private CatalogWorkbenchListener listener(File tempDir) throws IOException {
        File file = new File(tempDir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), "items: {}\n");
        ItemCatalogConfig catalog = new ItemCatalogConfig();
        catalog.load(fakePlugin(tempDir));
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenReturn(0);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, new ItemFactory(assembler));
        return new CatalogWorkbenchListener(registrar, catalog);
    }

    private static ItemStack[] grid(Material... materials) {
        ItemStack[] matrix = new ItemStack[9];
        for (int i = 0; i < materials.length; i++) {
            matrix[i] = materials[i] == null ? null : new ItemStack(materials[i]);
        }
        return matrix;
    }

    @Test
    void shapelessOverlappingListAndExactIngredientMatchesRegardlessOfSlotOrder(@TempDir File tempDir)
            throws IOException {
        CatalogWorkbenchListener listener = listener(tempDir);
        RecipeSpec spec = RecipeSpec.shapeless(List.of(
                RecipeIngredient.ofList("planks"),
                RecipeIngredient.ofMaterial(Material.OAK_PLANKS)), 1);

        // 貪欲割当だと OAK が先に list:planks を消費し SPRUCE が exact OAK_PLANKS に落ちて偽陰性
        // になっていた並び。二部マッチングでは両順序とも合致する。
        assertTrue(listener.matches(grid(Material.OAK_PLANKS, Material.SPRUCE_PLANKS), 3, spec));
        assertTrue(listener.matches(grid(Material.SPRUCE_PLANKS, Material.OAK_PLANKS), 3, spec));
    }

    @Test
    void shapelessStillRejectsGridsWithNoValidAssignment(@TempDir File tempDir) throws IOException {
        CatalogWorkbenchListener listener = listener(tempDir);
        RecipeSpec spec = RecipeSpec.shapeless(List.of(
                RecipeIngredient.ofList("planks"),
                RecipeIngredient.ofMaterial(Material.OAK_PLANKS)), 1);

        // SPRUCE×2: exact OAK_PLANKS 側を満たせるアイテムが存在しない → 不成立。
        assertFalse(listener.matches(grid(Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS), 3, spec));
        // 個数不一致も不成立のまま。
        assertFalse(listener.matches(grid(Material.OAK_PLANKS), 3, spec));
    }

    /** 非対称shape ["is", " s"] の正配置と左右反転配置 (3x3グリッド左上寄せ)。 */
    private static ItemStack[] canonicalAsymGrid() {
        return grid(Material.IRON_INGOT, Material.STICK, null,
                null, Material.STICK, null);
    }

    private static ItemStack[] mirroredAsymGrid() {
        return grid(Material.STICK, Material.IRON_INGOT, null,
                Material.STICK, null, null);
    }

    private static RecipeSpec asymSpec(boolean strictOrientation) {
        return RecipeSpec.shaped(List.of("is", " s"), Map.of(
                'i', RecipeIngredient.ofMaterial(Material.IRON_INGOT),
                's', RecipeIngredient.ofMaterial(Material.STICK)), 1)
                .withStrictOrientation(strictOrientation);
    }

    @Test
    void shapedDefaultAcceptsBothOrientationsLikeVanilla(@TempDir File tempDir) throws IOException {
        CatalogWorkbenchListener listener = listener(tempDir);
        RecipeSpec spec = asymSpec(false);
        // デフォルト (strict-orientation なし) はバニラ同様に反転配置も受理する。
        assertTrue(listener.matches(canonicalAsymGrid(), 3, spec));
        assertTrue(listener.matches(mirroredAsymGrid(), 3, spec));
    }

    @Test
    void shapedStrictOrientationRejectsMirroredGrid(@TempDir File tempDir) throws IOException {
        CatalogWorkbenchListener listener = listener(tempDir);
        RecipeSpec spec = asymSpec(true);
        assertTrue(listener.matches(canonicalAsymGrid(), 3, spec));
        // strict-orientation: true では登録した向き以外 (左右反転配置) を拒否する。
        assertFalse(listener.matches(mirroredAsymGrid(), 3, spec));
    }

    @Test
    void orientationCheckGateOnlyFiresForStrictAsymmetricShapes(@TempDir File tempDir) throws IOException {
        CatalogWorkbenchListener listener = listener(tempDir);
        // onPrepareCraft/onCrafterCraft の早期returnゲート: strict かつ非対称shapeのみ検証必須。
        // 条件が反転するとplainレシピの検証がスキップされて反転拒否が無効化されるため直接固定する。
        assertTrue(listener.needsOrientationCheck(asymSpec(true)));
        assertFalse(listener.needsOrientationCheck(asymSpec(false)));
        // 左右対称shapeはstrictでも検証不要 (向きの区別が存在しない)。
        RecipeSpec symmetric = RecipeSpec.shaped(List.of("i", "s", "s"), Map.of(
                'i', RecipeIngredient.ofMaterial(Material.IRON_INGOT),
                's', RecipeIngredient.ofMaterial(Material.STICK)), 1).withStrictOrientation(true);
        assertFalse(listener.needsOrientationCheck(symmetric));
        // shapeless は常に対象外。
        RecipeSpec shapeless = RecipeSpec.shapeless(List.of(
                RecipeIngredient.ofMaterial(Material.IRON_INGOT)), 1).withStrictOrientation(true);
        assertFalse(listener.needsOrientationCheck(shapeless));
    }

    // ------------------------------------------------------------------
    // D2: workbench(作業台専用) は2×2インベントリグリッドでは成立しない
    // ------------------------------------------------------------------

    private static ItemStack[] grid2x2(Material... materials) {
        ItemStack[] matrix = new ItemStack[4];
        for (int i = 0; i < materials.length; i++) {
            matrix[i] = materials[i] == null ? null : new ItemStack(materials[i]);
        }
        return matrix;
    }

    @Test
    void workbenchShapelessRecipeNeverMatchesA2x2Grid(@TempDir File tempDir) throws IOException {
        CatalogWorkbenchListener listener = listener(tempDir);
        RecipeSpec spec = RecipeSpec.shapeless(
                List.of(RecipeIngredient.ofMaterial(Material.STICK)), 1);

        assertFalse(listener.matches(grid2x2(Material.STICK), 2, spec));
        // 同じ盤面/仕様が3×3(作業台)グリッドでは成立することを対照として確認。
        assertTrue(listener.matches(grid(Material.STICK), 3, spec));
    }

    @Test
    void workbenchShapedRecipeThatFitsIn2x2StillNeverMatchesA2x2Grid(@TempDir File tempDir)
            throws IOException {
        CatalogWorkbenchListener listener = listener(tempDir);
        // shape自体は2×2に収まるが method=workbench なのでインベントリでは成立しない。
        RecipeSpec spec = RecipeSpec.shaped(List.of("AA", "AA"), Map.of(
                'A', RecipeIngredient.ofMaterial(Material.IRON_INGOT)), 1);

        assertFalse(listener.matches(grid2x2(
                Material.IRON_INGOT, Material.IRON_INGOT, Material.IRON_INGOT, Material.IRON_INGOT), 2, spec));
    }

    @Test
    void inventoryMethodRecipeMatchesA2x2Grid(@TempDir File tempDir) throws IOException {
        CatalogWorkbenchListener listener = listener(tempDir);
        RecipeSpec spec = RecipeSpec.shaped(RecipeSpec.Method.INVENTORY, List.of("AA", "AA"), Map.of(
                'A', RecipeIngredient.ofMaterial(Material.IRON_INGOT)), 1);

        assertTrue(listener.matches(grid2x2(
                Material.IRON_INGOT, Material.IRON_INGOT, Material.IRON_INGOT, Material.IRON_INGOT), 2, spec));
    }

    // ------------------------------------------------------------------
    // custom:<arsId> ingredient matching against an ExternalItemRegistry-registered ArsPaper
    // identity (source_gem craft-result bug fix). See ExternalItemRegistryTest for the reload
    // layering guarantees this depends on.
    // ------------------------------------------------------------------

    private static ItemStack withCustomModelData(Material material, int cmd) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(cmd);
        stack.setItemMeta(meta);
        return stack;
    }

    @Test
    void customIngredientMatchesAStackCarryingTheRegisteredExternalMaterialAndCmd(@TempDir File tempDir)
            throws IOException {
        ExternalItemRegistry.updateExternalPlugin("arspaper", Map.of("source_gem",
                new ExternalItemRegistry.Definition(
                        "source_gem", Material.PRISMARINE_SHARD, 100011, "Source Gem")));
        CatalogWorkbenchListener listener = listener(tempDir);
        RecipeSpec spec = RecipeSpec.shapeless(
                List.of(RecipeIngredient.ofCatalog("source_gem")), 1);

        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = withCustomModelData(Material.PRISMARINE_SHARD, 100011);
        assertTrue(listener.matches(matrix, 3, spec),
                "a stack carrying ArsPaper's registered material+CMD must satisfy custom:source_gem");
    }

    @Test
    void customIngredientRejectsAPlainStackOfTheSameMaterialWithoutTheRegisteredCmd(
            @TempDir File tempDir) throws IOException {
        ExternalItemRegistry.updateExternalPlugin("arspaper", Map.of("source_gem",
                new ExternalItemRegistry.Definition(
                        "source_gem", Material.PRISMARINE_SHARD, 100011, "Source Gem")));
        CatalogWorkbenchListener listener = listener(tempDir);
        RecipeSpec spec = RecipeSpec.shapeless(
                List.of(RecipeIngredient.ofCatalog("source_gem")), 1);

        // Bukkit's own MaterialChoice(PRISMARINE_SHARD) would accept this bare stack (that is the
        // exploit CatalogRecipeRegistrar registers as the ingredient choice); the listener's
        // per-slot identity check must veto it because it carries no CMD at all.
        ItemStack[] noCmd = new ItemStack[9];
        noCmd[0] = new ItemStack(Material.PRISMARINE_SHARD);
        assertFalse(listener.matches(noCmd, 3, spec),
                "a bare PRISMARINE_SHARD with no CustomModelData must NOT satisfy custom:source_gem");

        // Also reject a different CMD on the same base material (another catalog tier / unrelated item).
        ItemStack[] wrongCmd = new ItemStack[9];
        wrongCmd[0] = withCustomModelData(Material.PRISMARINE_SHARD, 1);
        assertFalse(listener.matches(wrongCmd, 3, spec),
                "a mismatched CustomModelData on the same material must NOT satisfy custom:source_gem");
    }

    @Test
    void plainIronToolIngredientStillAcceptsQualityStampedVanillaTool(@TempDir File tempDir)
            throws IOException {
        CatalogWorkbenchListener listener = listener(tempDir);
        RecipeSpec spec = RecipeSpec.shapeless(
                List.of(RecipeIngredient.ofMaterial(Material.IRON_SWORD)), 1);
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = sword.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(42L);
        data.setQuality(7);
        sword.setItemMeta(meta);
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = sword;

        assertTrue(listener.matches(matrix, 3, spec),
                "quality PDC alone must not make a vanilla iron sword fail a material ingredient");
    }

    @Test
    void explicitIronToolListAcceptsCatalogIdentityWithoutWeakeningPlainMaterialRules(
            @TempDir File tempDir) throws IOException {
        MaterialLists.update(
                Map.of("spellbook_iron_swords", Set.of(Material.IRON_SWORD)),
                Map.of("spellbook_iron_swords", Set.of("iron_dagger")),
                Map.of());
        CatalogWorkbenchListener listener = listener(tempDir);
        RecipeSpec listSpec = RecipeSpec.shapeless(
                List.of(RecipeIngredient.ofList("spellbook_iron_swords")), 1);
        RecipeSpec plainSpec = RecipeSpec.shapeless(
                List.of(RecipeIngredient.ofMaterial(Material.IRON_SWORD)), 1);
        ItemStack dagger = withCustomModelData(Material.IRON_SWORD, 5);
        ItemMeta meta = dagger.getItemMeta();
        ItemData.of(meta).setCatalogId("iron_dagger");
        dagger.setItemMeta(meta);
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = dagger;

        assertTrue(listener.matches(matrix, 3, listSpec));
        assertFalse(listener.matches(matrix, 3, plainSpec),
                "plain material identity guard remains closed for recipes that did not opt in");
    }

    @Test
    void catalogPdcWithoutCustomModelDataIsTreatedAsVanilla(
            @TempDir File tempDir) throws IOException {
        CatalogWorkbenchListener listener = listener(tempDir);
        RecipeSpec plainSpec = RecipeSpec.shapeless(
                List.of(RecipeIngredient.ofMaterial(Material.IRON_SWORD)), 1);
        RecipeSpec customSpec = RecipeSpec.shapeless(
                List.of(RecipeIngredient.ofCatalog("iron_dagger")), 1);
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = sword.getItemMeta();
        ItemData.of(meta).setCatalogId("iron_dagger");
        sword.setItemMeta(meta);
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = sword;

        assertTrue(listener.matches(matrix, 3, plainSpec),
                "an item without CustomModelData must be treated as vanilla even if catalogId PDC remains");
        assertFalse(listener.matches(matrix, 3, customSpec),
                "catalogId PDC alone must not satisfy a custom ingredient without CustomModelData");
    }
}
