package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.ExternalItemRegistry;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.RecipeIngredient;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>ユーザー報告(2026-08-19)「スクラップをクラフトして鉱石に戻そうとすると、このアイテムは
 * 見た目が同じでも違うアイテムですと出て戻せなかった。ちゃんと正しいアイテムを使っている」</b>の回帰ガード。
 *
 * <p>真因は「スクラップの identity がずれている」ではなく、<b>TF が自分のレシピを他人のレシピと
 * 誤認して自分で殺していた</b>こと。{@code CatalogRecipeRegistrar#registerAddedRecipes} は
 * {@code progression/crafting-features.yml} の {@code added-recipes}(スクラップ4個→インゴットの7件)を
 * {@code Bukkit.addRecipe} してキーは覚えるが、<b>{@code registeredSpecs} には載せていなかった</b>。
 * {@code CatalogWorkbenchListener} は「選択レシピが {@code registeredSpecs} に無い」ものを
 * 他プラグイン/バニラのレシピとみなすので、
 * <ol>
 *   <li>{@code registeredOf(selected)} が空 → 「カタログ品を食おうとしている他人のレシピ」扱い、</li>
 *   <li>{@code foreignRecipeOwnsGridItems} は namespace が {@code trinityforge} なので false
 *       (スクラップの所有プラグインは {@code arspaper})、</li>
 *   <li>スクラップは重ねられるので装備の素通しにも当たらず、</li>
 *   <li>{@code rematch} が走査する {@code allRegistered()} にも added-recipes は<b>入っていない</b>、</li>
 * </ol>
 * で結果枠がクリアされ、あの名指しメッセージが出ていた。
 *
 * <p>つまり <b>{@code custom:} 素材を使う {@code added-recipes} は例外なく永久にクラフト不可</b>
 * だったということ ── レシピ帳には出るので「設定が効いていない」ようにしか見えない。
 * ここで固定するのは「added-recipes も TF 自身のレシピとして扱われること」。
 */
class CatalogWorkbenchAddedRecipeTest {

    private static final String PLUGIN_LAYER = "arspaper";
    /** ArsPaper materials.yml の実値 (base_material: IRON_NUGGET / custom_model_data: 5313)。 */
    private static final int IRON_SCRAP_CMD = 5313;
    private static final String IRON_SCRAP_ID = "iron_ingot_scrap";

    private CatalogWorkbenchListener listener;
    private Recipe addedRecipe;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws IOException {
        MockBukkit.mock();
        // スクラップは ArsPaper の materials.yml 側のアイテム。TF カタログには居ない。
        ExternalItemRegistry.updateExternalPlugin(PLUGIN_LAYER, Map.of(
                IRON_SCRAP_ID, new ExternalItemRegistry.Definition(
                        IRON_SCRAP_ID, Material.IRON_NUGGET, IRON_SCRAP_CMD, "鉄スクラップ")));

        ItemCatalogConfig catalog = loadEmptyCatalog(tempDir);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler(), () -> List.of(scrapToIngot()));
        registrar.registerAll();

        addedRecipe = Bukkit.getRecipe(new NamespacedKey("trinityforge", "added_1"));
        assertNotNull(addedRecipe, "added-recipes が Bukkit へ登録できていない(テスト前提の崩れ)");

        listener = new CatalogWorkbenchListener(registrar, catalog);
    }

    @AfterEach
    void tearDown() {
        ExternalItemRegistry.updateExternalPlugin(PLUGIN_LAYER, Map.of());
        MockBukkit.unmock();
    }

    /** {@code crafting-features.yml} の実物と同じ形: スクラップ4個(2×2) → 鉄インゴット1個。 */
    private static CraftingFeaturesConfig.AddedRecipe scrapToIngot() {
        Map<Character, RecipeIngredient> ingredients = new LinkedHashMap<>();
        ingredients.put('i', RecipeIngredient.ofCatalog(IRON_SCRAP_ID));
        RecipeSpec spec = RecipeSpec.shaped(
                RecipeSpec.Method.INVENTORY, List.of("ii", "ii"), ingredients, 1);
        return new CraftingFeaturesConfig.AddedRecipe(Material.IRON_INGOT, 1, spec);
    }

    /** 見た目は素の鉄塊そのままの「鉄スクラップ」。 */
    private static ItemStack ironScrap() {
        ItemStack stack = new ItemStack(Material.IRON_NUGGET);
        stack.editMeta(meta -> meta.setCustomModelData(IRON_SCRAP_CMD));
        return stack;
    }

    private static ItemStack[] fullScrapGrid() {
        return new ItemStack[] {ironScrap(), ironScrap(), ironScrap(), ironScrap()};
    }

    @Test
    @DisplayName("added-recipes は TF 自身のレシピとして登録される(他人のレシピ扱いされない)")
    void addedRecipesAreExposedAsOurOwnRegisteredRecipes(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadEmptyCatalog(tempDir);
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), catalog, factoryWithMockAssembler(), () -> List.of(scrapToIngot()));
        registrar.registerAll();

        NamespacedKey key = new NamespacedKey("trinityforge", "added_1");
        CatalogRecipeRegistrar.RegisteredRecipe registered = registrar.registered(key).orElseThrow(
                () -> new AssertionError("added-recipes が registeredSpecs に載っていない ——"
                        + " CatalogWorkbenchListener はこれを他人のレシピとみなして結果枠を消す"));

        assertEquals(Material.IRON_INGOT, registrar.resultOf(registered).getType());
        assertTrue(registrar.allRegistered().contains(registered),
                "rematch が走査する allRegistered() に added-recipes が入っていない");
    }

    @Test
    @DisplayName("スクラップ4個の盤面で結果枠が消されない(報告そのもの)")
    void scrapGridIsNotBlockedByOurOwnGuard() {
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getMatrix()).thenReturn(fullScrapGrid());
        PrepareItemCraftEvent event = mock(PrepareItemCraftEvent.class);
        when(event.getRecipe()).thenReturn(addedRecipe);
        when(event.getInventory()).thenReturn(inventory);

        listener.onPrepareCraft(event);

        verify(inventory, never()).setResult(any());
    }

    /**
     * プレビューと取り出しは別イベント。片方だけ通しても「見えるのに取れない」で残るので、
     * 「作れる」を主張するテストは必ず両方通す(2026-08-18 に同じ失敗を踏んでいる)。
     */
    @Test
    @DisplayName("スクラップ4個は結果を取り出すところまで通る")
    void scrapGridSurvivesTheTakeGate() {
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getMatrix()).thenReturn(fullScrapGrid());
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getRecipe()).thenReturn(addedRecipe);
        when(event.getInventory()).thenReturn(inventory);

        listener.onCraftItem(event);

        verify(event, never()).setCancelled(true);
    }

    // ------------------------------------------------------------------
    // harness (CatalogRecipeRegistrarTest と同じ作り)
    // ------------------------------------------------------------------

    private static ItemCatalogConfig loadEmptyCatalog(File tempDir) throws IOException {
        File file = new File(tempDir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), "items: {}\n");
        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CatalogWorkbenchAddedRecipeTest");
            case "saveResource" -> throw new AssertionError("file exists; saveResource must not be called");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static ItemFactory factoryWithMockAssembler() {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenReturn(0);
        return new ItemFactory(assembler);
    }
}
