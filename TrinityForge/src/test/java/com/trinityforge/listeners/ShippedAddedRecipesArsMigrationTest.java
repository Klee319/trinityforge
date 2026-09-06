package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-08-25: ArsPaper フォーク {@code items.yml} の {@code items:} に最後まで残っていた2件
 * (trident / エンチャントされた金リンゴ) を {@code progression/crafting-features.yml} の
 * {@code added-recipes:} へ移設した回帰ガード。
 *
 * <p>ここで固定するのは2点(タスク指示どおり):
 * <ol>
 *   <li><b>移設後の2件が TF 側の設定から実際に読める / Bukkit レシピとして登録できること</b>
 *       ({@link #shippedConfigStillContainsBothMigratedRecipes()} /
 *       {@link #bothMigratedRecipesRegisterAsBukkitRecipes(File)}) — 出荷 yml から
 *       {@code result: TRIDENT} / {@code result: ENCHANTED_GOLDEN_APPLE} を消すと落ちる。</li>
 *   <li><b>ゲート id が(Ars 側を含む)どの {@code recipe:<id>} 解放条件とも衝突しないこと</b>
 *       ({@link #migratedRecipeKeysAreStructurallyUngateable(File)}) —
 *       {@code CatalogCraftGateListener#resolveGateId} は namespace {@code trinityforge} で
 *       path が {@code catalog_} 始まりでないキーを常に {@code null}(=ゲート対象外)として扱う。
 *       {@code added-recipes} のキーは常に {@code trinityforge:added_<n>} なので、
 *       スキルツリー側がどんな {@code recipe:<id>} を配置していようと、この2件が誤って
 *       ロックされる/ロックを共有することは構造的に起きない。この assert が緩んで
 *       {@code resolveGateId} が非 null を返すようになったら、初めて衝突の可能性が生まれる。</li>
 * </ol>
 */
class ShippedAddedRecipesArsMigrationTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedAddedRecipesArsMigrationTest");
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

    /** 出荷リソースの bytes をそのままデータフォルダへ置いて読み込む(手書きの写しを作らない)。 */
    private static CraftingFeaturesConfig loadShippedCraftingFeatures(File tempDir) throws IOException {
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (var in = ShippedAddedRecipesArsMigrationTest.class.getClassLoader()
                .getResourceAsStream(CraftingFeaturesConfig.PATH.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + CraftingFeaturesConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "出荷ymlがパースできない");
        return config;
    }

    private static ItemCatalogConfig loadEmptyCatalog(File tempDir) throws IOException {
        File file = new File(tempDir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), "items: {}\n");
        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    private static ItemFactory factoryWithMockAssembler() {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenReturn(0);
        return new ItemFactory(assembler);
    }

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // === 1. 移設後の2件が TF 側の設定から読めること ===

    @Test
    @DisplayName("出荷 crafting-features.yml の added-recipes に trident / エンチャントされた金リンゴ が"
            + "そのまま存在する(Ars items.yml からの移設が生きている)")
    void shippedConfigStillContainsBothMigratedRecipes(@TempDir File tempDir) throws IOException {
        List<CraftingFeaturesConfig.AddedRecipe> added = loadShippedCraftingFeatures(tempDir).addedRecipes();

        assertTrue(added.stream().anyMatch(r -> r.result() == Material.TRIDENT),
                "added-recipes に result: TRIDENT が無い(items.yml からの移設が失われている)");
        assertTrue(added.stream().anyMatch(r -> r.result() == Material.ENCHANTED_GOLDEN_APPLE),
                "added-recipes に result: ENCHANTED_GOLDEN_APPLE が無い(items.yml からの移設が失われている)");
    }

    @Test
    @DisplayName("移設後の2件は実際に Bukkit レシピとして登録される(自分の登録として扱われる)")
    void bothMigratedRecipesRegisterAsBukkitRecipes(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadEmptyCatalog(tempDir);
        CraftingFeaturesConfig crafting = loadShippedCraftingFeatures(tempDir);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler(), crafting::addedRecipes);
        registrar.registerAll();

        boolean tridentRegistered = registrar.allRegistered().stream()
                .anyMatch(r -> registrar.resultOf(r).getType() == Material.TRIDENT);
        boolean appleRegistered = registrar.allRegistered().stream()
                .anyMatch(r -> registrar.resultOf(r).getType() == Material.ENCHANTED_GOLDEN_APPLE);

        assertTrue(tridentRegistered, "TRIDENT の added-recipes が registeredSpecs に載っていない"
                + "(CatalogWorkbenchListener が『他人のレシピ』とみなし永久にクラフト不可になる)");
        assertTrue(appleRegistered, "ENCHANTED_GOLDEN_APPLE の added-recipes が registeredSpecs に載っていない"
                + "(CatalogWorkbenchListener が『他人のレシピ』とみなし永久にクラフト不可になる)");
    }

    // === 2. ゲート id が Ars 側と衝突しないこと(構造的な保証) ===

    @Test
    @DisplayName("移設後の2件のレシピキーは resolveGateId が常に null を返す"
            + "(catalog_ 接頭辞を持たない trinityforge 名前空間 = ゲート対象外。"
            + "Ars 側の gate id とは namespace が異なる上にそもそもゲート判定を通らないため衝突しえない)")
    void migratedRecipeKeysAreStructurallyUngateable(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadEmptyCatalog(tempDir);
        CraftingFeaturesConfig crafting = loadShippedCraftingFeatures(tempDir);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler(), crafting::addedRecipes);
        registrar.registerAll();

        List<CraftingFeaturesConfig.AddedRecipe> added = crafting.addedRecipes();
        int tridentIndex = -1;
        int appleIndex = -1;
        for (int i = 0; i < added.size(); i++) {
            if (added.get(i).result() == Material.TRIDENT) {
                tridentIndex = i + 1; // registerAddedRecipes は 1-based index で "added_<n>" を振る
            }
            if (added.get(i).result() == Material.ENCHANTED_GOLDEN_APPLE) {
                appleIndex = i + 1;
            }
        }
        assertTrue(tridentIndex > 0, "テスト前提が崩れている: TRIDENT が added-recipes に無い");
        assertTrue(appleIndex > 0, "テスト前提が崩れている: ENCHANTED_GOLDEN_APPLE が added-recipes に無い");

        NamespacedKey tridentKey = new NamespacedKey("trinityforge", "added_" + tridentIndex);
        NamespacedKey appleKey = new NamespacedKey("trinityforge", "added_" + appleIndex);

        Recipe tridentRecipe = org.bukkit.Bukkit.getRecipe(tridentKey);
        Recipe appleRecipe = org.bukkit.Bukkit.getRecipe(appleKey);
        assertNotNull(tridentRecipe, "計算した added_" + tridentIndex + " キーで Bukkit レシピが引けない"
                + "(index の数え方がレジストラの実装とずれている)");
        assertNotNull(appleRecipe, "計算した added_" + appleIndex + " キーで Bukkit レシピが引けない"
                + "(index の数え方がレジストラの実装とずれている)");

        assertNull(CatalogCraftGateListener.resolveGateId(tridentKey),
                "trident の移設後キーがゲート対象になっている。skilltree 側で偶然 'added_"
                + tridentIndex + "' という recipe:id が使われた場合に誤ってロックされる経路が生まれた");
        assertNull(CatalogCraftGateListener.resolveGateId(appleKey),
                "エンチャントされた金リンゴ の移設後キーがゲート対象になっている。skilltree 側で偶然 'added_"
                + appleIndex + "' という recipe:id が使われた場合に誤ってロックされる経路が生まれた");

        // 対照: catalog_ 接頭辞を持つキーは(存在すれば)ゲート対象になりうることの確認
        // — resolveGateId 自体が壊れて「常に null」を返しているだけではないことの反証。
        assertEquals("some_id",
                CatalogCraftGateListener.resolveGateId(new NamespacedKey("trinityforge", "catalog_some_id")));
    }
}
