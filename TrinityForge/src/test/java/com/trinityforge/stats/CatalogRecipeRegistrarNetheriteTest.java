package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmithingTransformRecipe;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * U7 原因2 の再発防止テスト: {@code items/catalog.yml} の {@code method: netherite} が Bukkit の
 * {@link SmithingTransformRecipe} として実際に登録されることを検証する。
 *
 * <p>登録が無いと 1.21.2 以降のスミス台は base スロットにアイテムを置かせない
 * ({@code RecipePropertySet.SMITHING_BASE} は「読み込み済みスミスレシピの base ingredient」から
 * 組み立てられ、CraftBukkit の {@code Bukkit.addRecipe} がその再構築を走らせる)。
 * つまり弓/クロスボウ/トライデント/メイス/ブレイズロッドのように<b>バニラのネザライト強化に
 * 存在しない材質</b>は、TF がレシピを登録しない限り物理的に置けず
 * {@code CatalogSmithingListener} まで到達しない。
 */
class CatalogRecipeRegistrarNetheriteTest {

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
            case "getLogger" -> Logger.getLogger("CatalogRecipeRegistrarNetheriteTest");
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

    /** 出荷 {@code src/main/resources/items/catalog.yml} をそのままロードする。 */
    private static ItemCatalogConfig loadShippedCatalog(File tempDir) throws IOException {
        return loadCatalog(tempDir, Files.readString(Path.of("src/main/resources/items/catalog.yml")));
    }

    private static ItemFactory factoryWithMockAssembler() {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenReturn(0);
        return new ItemFactory(assembler);
    }

    private static List<SmithingTransformRecipe> trinityForgeSmithingRecipes() {
        List<SmithingTransformRecipe> found = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (recipe instanceof SmithingTransformRecipe smithing
                    && "trinityforge".equals(smithing.getKey().getNamespace())) {
                found.add(smithing);
            }
        }
        return found;
    }

    /** 出荷カタログが持つ {@code method: netherite} の (結果ID → 素材ID) 一覧。 */
    private static Map<String, String> shippedNetheriteSpecs(ItemCatalogConfig catalog) {
        Map<String, String> specs = new LinkedHashMap<>();
        catalog.all().forEach((id, template) -> {
            for (RecipeSpec spec : template.recipes()) {
                if (spec.isNetherite() && spec.shouldRegister()) {
                    specs.put(id, spec.sourceItem());
                }
            }
        });
        return specs;
    }

    // ------------------------------------------------------------------
    // 出荷カタログ: netherite メソッドが「衝突しない限り」登録されること
    // ------------------------------------------------------------------

    /**
     * <b>この件数一致は MockBukkit だから成立する。実サーバでは 12 件中 5 件しか登録されない。</b>
     *
     * <p>{@link CatalogRecipeRegistrar} は {@code NetheriteUpgradeGuard} で
     * 「同じ3点(テンプレ/base/インゴット)に一致する他所のレシピが既にある材質」への登録を避ける。
     * MockBukkit は<b>バニラのスミスレシピを1件も持たない</b>ので誰とも衝突せず全件登録されるが、
     * 実サーバでは出荷の source 12 件のうち DIAMOND_SWORD×5 / DIAMOND_AXE / DIAMOND_HOE の
     * <b>7 件がバニラの {@code netherite_*_smithing} に当たって除外される</b>
     * （除外されても壊れない ── それらは<b>バニラのレシピが一致するおかげで</b>
     *  {@code PrepareSmithingEvent} が飛び、{@code CatalogSmithingListener} が結果を差し替える）。
     *
     * <p>したがってこのテストが固定しているのは「登録処理そのものが正しく動くこと」であって、
     * <b>「実サーバで12件出ること」ではない</b>。U7 が実際に必要としていた
     * 非バニラ base の 5 件は {@link #reportedBrokenNetheriteItemsRegisterTheirNonVanillaBaseMaterial}
     * が、衝突時に降りることは
     * {@link #netheriteRecipeIsSkippedWhenAnExistingSmithingRecipeAlreadyMatchesTheSameTriple}
     * が別々に固定している。
     */
    @Test
    void everyShippedNetheriteRecipeRegistersWhenNothingElseClaimsTheSameTriple(@TempDir File tempDir)
            throws IOException {
        ItemCatalogConfig catalog = loadShippedCatalog(tempDir);
        Map<String, String> specs = shippedNetheriteSpecs(catalog);
        assertFalse(specs.isEmpty(), "出荷 catalog.yml から method: netherite が 1 件も読めていない");

        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());
        registrar.registerAll();

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : specs.entrySet()) {
            NamespacedKey key = new NamespacedKey("trinityforge", "catalog_" + entry.getKey() + "_smithing");
            Recipe recipe = Bukkit.getRecipe(key);
            if (!(recipe instanceof SmithingTransformRecipe smithing)) {
                missing.add(entry.getKey() + " (source=" + entry.getValue() + ")");
                continue;
            }
            Material expectedBase = catalog.template(entry.getValue()).orElseThrow().material();
            assertTrue(smithing.getBase().test(new ItemStack(expectedBase)),
                    entry.getKey() + " の base が " + expectedBase + " を受け付けない");
            assertTrue(smithing.getTemplate().test(
                            new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE)),
                    entry.getKey() + " の template がネザライト強化テンプレートを受け付けない");
            assertTrue(smithing.getAddition().test(new ItemStack(Material.NETHERITE_INGOT)),
                    entry.getKey() + " の addition がネザライトインゴットを受け付けない");
        }
        assertTrue(missing.isEmpty(), "method: netherite が Bukkit に登録されていない: " + missing);
        assertEquals(specs.size(), trinityForgeSmithingRecipes().size(),
                "登録された TF スミス台レシピ数が method: netherite の件数と一致しない"
                        + "(MockBukkit にはバニラのスミスレシピが無いので全件登録されるのが正。"
                        + "実サーバでは NetheriteUpgradeGuard が7件を除外して5件になる)");
    }

    /**
     * U7 で実際に「スミス台に置けない」と報告された 4 件が、base スロットに置けるだけの
     * レシピを確かに持つこと。{@code RecipePropertySet.SMITHING_BASE} はこれらの base
     * ingredient から組み立てられるので、この 4 件の登録がそのままスロット解放になる。
     */
    @Test
    void reportedBrokenNetheriteItemsRegisterTheirNonVanillaBaseMaterial(@TempDir File tempDir)
            throws IOException {
        ItemCatalogConfig catalog = loadShippedCatalog(tempDir);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());
        registrar.registerAll();

        Map<String, Material> expected = Map.of(
                "netherite_bow", Material.BOW,
                "netherite_trident", Material.TRIDENT,
                "netherite_mace", Material.MACE,
                "netherite_crossbow", Material.CROSSBOW,
                "netherite_wand", Material.BLAZE_ROD);
        expected.forEach((id, base) -> {
            Recipe recipe = Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_" + id + "_smithing"));
            SmithingTransformRecipe smithing = assertInstanceOf(SmithingTransformRecipe.class, recipe,
                    id + " のスミス台レシピが登録されていない");
            assertTrue(smithing.getBase().test(new ItemStack(base)),
                    id + " の base が " + base + " を受け付けない");
        });
    }

    // ------------------------------------------------------------------
    // 単体挙動
    // ------------------------------------------------------------------

    @Test
    void netheriteRecipeResultCarriesCatalogIdentityWithoutRollSeed(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  diamond_bow:
                    material: BOW
                    custom-model-data: 1096
                  netherite_bow:
                    material: BOW
                    custom-model-data: 1097
                    recipe:
                      method: netherite
                      source-item: custom:diamond_bow
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        Recipe recipe = Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_netherite_bow_smithing"));
        SmithingTransformRecipe smithing = assertInstanceOf(SmithingTransformRecipe.class, recipe);
        var data = com.trinityforge.pdc.ItemData.of(smithing.getResult().getItemMeta());
        assertEquals("netherite_bow", data.catalogId().orElseThrow());
        assertTrue(data.rollSeed().isEmpty(),
                "結果は identity のみ — 実際の品質は CatalogSmithingListener が振り直す");
        assertFalse(smithing.willCopyDataComponents(),
                "base の data component を引き継ぐと素材側の PDC が結果に混ざる");
    }

    /**
     * SPIGOT-4638「last recipe gets priority」— プラグインが後から足したレシピはバニラより
     * 優先される。よって base=DIAMOND_SWORD を足すと「ただのダイヤの剣＋インゴット」が
     * バニラのネザライトの剣ではなく TF のアイテムに化ける。同じ 3 点に一致する既存レシピが
     * あるときは登録を見送ること。
     */
    @Test
    void netheriteRecipeIsSkippedWhenAnExistingSmithingRecipeAlreadyMatchesTheSameTriple(
            @TempDir File tempDir) throws IOException {
        Bukkit.addRecipe(new SmithingTransformRecipe(
                new NamespacedKey("minecraft", "netherite_sword_smithing"),
                new ItemStack(Material.NETHERITE_SWORD),
                new RecipeChoice.MaterialChoice(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new RecipeChoice.MaterialChoice(Material.DIAMOND_SWORD),
                new RecipeChoice.MaterialChoice(Material.NETHERITE_INGOT)));

        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  diamond_dagger:
                    material: DIAMOND_SWORD
                    custom-model-data: 1101
                  netherite_dagger:
                    material: NETHERITE_SWORD
                    recipe:
                      method: netherite
                      source-item: custom:diamond_dagger
                  diamond_bow:
                    material: BOW
                    custom-model-data: 1096
                  netherite_bow:
                    material: BOW
                    custom-model-data: 1097
                    recipe:
                      method: netherite
                      source-item: custom:diamond_bow
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        assertNull(Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_netherite_dagger_smithing")),
                "バニラのネザライトの剣を奪うレシピを登録してはいけない");
        assertNotNull(Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_netherite_bow_smithing")),
                "衝突しない BOW 側は登録されなければならない");
    }

    /** 防具トリムは template/addition が違うので「衝突あり」と誤判定してはいけない。 */
    @Test
    void armorTrimRecipeDoesNotBlockANetheriteRecipeOnTheSameBaseMaterial(@TempDir File tempDir)
            throws IOException {
        Bukkit.addRecipe(new SmithingTransformRecipe(
                new NamespacedKey("minecraft", "sentry_armor_trim_smithing_template_smithing"),
                new ItemStack(Material.DIAMOND_HELMET),
                new RecipeChoice.MaterialChoice(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
                new RecipeChoice.MaterialChoice(Material.DIAMOND_HELMET),
                new RecipeChoice.MaterialChoice(Material.EMERALD)));

        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  diamond_crown:
                    material: DIAMOND_HELMET
                    custom-model-data: 900
                  netherite_crown:
                    material: NETHERITE_HELMET
                    recipe:
                      method: netherite
                      source-item: custom:diamond_crown
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        assertNotNull(Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_netherite_crown_smithing")));
    }

    @Test
    void netheriteRecipeWithAnUnknownSourceItemIsSkipped(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  netherite_bow:
                    material: BOW
                    recipe:
                      method: netherite
                      source-item: custom:not_in_catalog
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        assertNull(Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_netherite_bow_smithing")));
    }

    @Test
    void registerAllIsIdempotentForNetheriteRecipes(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  diamond_bow:
                    material: BOW
                    custom-model-data: 1096
                  netherite_bow:
                    material: BOW
                    custom-model-data: 1097
                    recipe:
                      method: netherite
                      source-item: custom:diamond_bow
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();
        registrar.registerAll();
        registrar.registerAll();

        assertEquals(1, trinityForgeSmithingRecipes().size());
    }

    @Test
    void netheriteRecipeIsRemovedWhenDroppedFromTheCatalogOnReload(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), """
                items:
                  diamond_bow:
                    material: BOW
                    custom-model-data: 1096
                  netherite_bow:
                    material: BOW
                    custom-model-data: 1097
                    recipe:
                      method: netherite
                      source-item: custom:diamond_bow
                """);
        ItemCatalogConfig catalog = new ItemCatalogConfig();
        catalog.load(fakePlugin(tempDir));
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());
        registrar.registerAll();
        assertEquals(1, trinityForgeSmithingRecipes().size());

        Files.writeString(file.toPath(), """
                items:
                  diamond_bow:
                    material: BOW
                    custom-model-data: 1096
                  netherite_bow:
                    material: BOW
                    custom-model-data: 1097
                """);
        catalog.load(fakePlugin(tempDir));
        registrar.registerAll();

        assertEquals(0, trinityForgeSmithingRecipes().size());
    }

    /** {@code register: false} は従来どおり「データは残すが登録しない」。 */
    @Test
    void netheriteRecipeWithRegisterFalseIsNotRegistered(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  diamond_bow:
                    material: BOW
                    custom-model-data: 1096
                  netherite_bow:
                    material: BOW
                    custom-model-data: 1097
                    recipe:
                      method: netherite
                      source-item: custom:diamond_bow
                      register: false
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        assertEquals(0, trinityForgeSmithingRecipes().size());
    }

    /** 通常のクラフトレシピ側のキー採番が netherite の追加で崩れていないこと。 */
    @Test
    void workbenchRecipeKeyNumberingIsUnaffectedByNetheriteRecipes(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  diamond_bow:
                    material: BOW
                    custom-model-data: 1096
                  netherite_bow:
                    material: BOW
                    custom-model-data: 1097
                    recipes:
                      - method: netherite
                        source-item: custom:diamond_bow
                      - type: shapeless
                        ingredients: [STICK]
                      - type: shapeless
                        ingredients: [DIAMOND]
                """);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler());

        registrar.registerAll();

        assertNotNull(Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_netherite_bow_smithing")));
        assertNotNull(Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_netherite_bow")));
        assertNotNull(Bukkit.getRecipe(new NamespacedKey("trinityforge", "catalog_netherite_bow_2")));
    }
}
