package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.ExternalItemRegistry;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Crafter;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CrafterInventory;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「ソースジャー／台座／写字台が作れない」の回帰ガード (D5, 2026-07-31)。
 *
 * <p>症状の真因は ArsPaper 側ではなく <b>TF の {@link CatalogWorkbenchListener} が ArsPaper 自身の
 * 作業台レシピの結果枠を毎回 null にしていた</b>こと。{@code catalogIdentityOf} は
 * PDC → {@code catalog.yml} → {@link ExternalItemRegistry} の3段で解決するので、ArsPaper が
 * enable 時に自分の全カスタム品を push した結果<b>他プラグインの品まで「カタログ品」に化け</b>、
 * 「カタログ品を消費できるのはオプトインした TF レシピだけ」という規則が ArsPaper 自身のレシピに
 * 当たっていた ({@code ours.isEmpty()} → rematch 失敗 → {@code setResult(null)})。
 * さらに {@code onCraftItem}(HIGHEST) が同条件で {@code setCancelled(true)} するため、
 * フォーク側で結果枠を書き戻しても取り出せない。
 *
 * <p>採用した緩和は<b>namespace 一致版</b>: 盤面のカスタム品が<em>すべて</em>選択レシピの
 * 所有プラグインのものであるときだけ TF が介入を見送る。「外部品が乗っていれば常に見送る」まで
 * 緩めるとバニラレシピが ArsPaper の圧縮品を溶かせるようになるため、
 * {@link #vanillaRecipeStillCannotConsumeAnArsPaperCompressedItem()} で下限を固定している。
 */
class CatalogWorkbenchListenerForeignPluginRecipeTest {

    /** ArsPaper の {@code source_berry} = GLOW_BERRIES / CMD 100010 (実値)。 */
    private static final int SOURCE_BERRY_CMD = 100010;
    /** ArsPaper 側の圧縮チェーンの一例 (バニラレシピに溶かされてはいけない品)。 */
    private static final int IRON_BLOCK_1X_CMD = 100200;

    private CatalogWorkbenchListener listener;
    private CatalogRecipeRegistrar registrar;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        // ArsPaper が enable 時に itemRegistry.getAll() 全件を push した状態を再現する。
        ExternalItemRegistry.updateExternalPlugin("arspaper", Map.of(
                "source_berry", new ExternalItemRegistry.Definition(
                        "source_berry", Material.GLOW_BERRIES, SOURCE_BERRY_CMD, "ソースベリー"),
                "iron_block_1x", new ExternalItemRegistry.Definition(
                        "iron_block_1x", Material.IRON_BLOCK, IRON_BLOCK_1X_CMD, "圧縮鉄ブロック")));
        registrar = mock(CatalogRecipeRegistrar.class);
        // 盤面に合う TF カタログレシピは1本も無い = 従来の経路なら必ず結果枠がクリアされる状況。
        when(registrar.registered(any())).thenReturn(Optional.empty());
        when(registrar.allRegistered()).thenReturn(List.of());
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.all()).thenReturn(Map.of());
        listener = new CatalogWorkbenchListener(registrar, catalog);
    }

    @AfterEach
    void tearDown() {
        // static レジストリを必ず戻す。残すと別テストが偽陽性で緑になる。
        ExternalItemRegistry.updateExternalPlugin("arspaper", Map.of());
        ExternalItemRegistry.updateExternalPlugin("otherplugin", Map.of());
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static ItemStack withCustomModelData(Material material, int cmd) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(cmd);
        stack.setItemMeta(meta);
        return stack;
    }

    /** PDC {@code trinityforge:catalog_id} が押された TF 所有のカタログ品。 */
    private static ItemStack tfCatalogItem(Material material, int cmd, String catalogId) {
        ItemStack stack = withCustomModelData(material, cmd);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(catalogId);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * ArsPaper 登録の {@code source_jar} レシピ (BRICK×8 + {@code custom:source_berry})。
     * フォークは {@code custom:} 素材を MaterialChoice で登録するのでここも材質のみで組む。
     */
    private static CraftingRecipe arsSourceJarRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey("arspaper", "source_jar"), new ItemStack(Material.GLASS));
        recipe.shape("GGG", "GSG", "GGG");
        recipe.setIngredient('G', Material.BRICK);
        recipe.setIngredient('S', Material.GLOW_BERRIES);
        return recipe;
    }

    /** バニラの「鉄ブロック→鉄インゴット9個」相当。圧縮品を溶かす経路の代表。 */
    private static CraftingRecipe vanillaIronIngotRecipe() {
        ShapelessRecipe recipe = new ShapelessRecipe(
                new NamespacedKey("minecraft", "iron_ingot_from_iron_block"),
                new ItemStack(Material.IRON_INGOT, 9));
        recipe.addIngredient(Material.IRON_BLOCK);
        return recipe;
    }

    /** BRICK×8 + 中央に ArsPaper のソースベリー。 */
    private static ItemStack[] sourceJarGrid() {
        ItemStack[] matrix = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            matrix[i] = new ItemStack(Material.BRICK);
        }
        matrix[4] = withCustomModelData(Material.GLOW_BERRIES, SOURCE_BERRY_CMD);
        return matrix;
    }

    private static ItemStack[] singleItemGrid(ItemStack item) {
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = item;
        return matrix;
    }

    private PrepareItemCraftEvent prepareEvent(Recipe recipe, ItemStack[] matrix) {
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getMatrix()).thenReturn(matrix);
        PrepareItemCraftEvent event = mock(PrepareItemCraftEvent.class);
        when(event.getRecipe()).thenReturn(recipe);
        when(event.getInventory()).thenReturn(inventory);
        return event;
    }

    private CraftItemEvent craftEvent(Recipe recipe, ItemStack[] matrix) {
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getMatrix()).thenReturn(matrix);
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getRecipe()).thenReturn(recipe);
        when(event.getInventory()).thenReturn(inventory);
        return event;
    }

    private CrafterCraftEvent crafterEvent(CraftingRecipe recipe, ItemStack[] matrix) {
        CrafterCraftEvent event = mock(CrafterCraftEvent.class);
        Block block = mock(Block.class);
        Crafter crafter = mock(Crafter.class);
        CrafterInventory inventory = mock(CrafterInventory.class);
        when(event.getRecipe()).thenReturn(recipe);
        when(event.getBlock()).thenReturn(block);
        when(block.getState()).thenReturn(crafter);
        when(crafter.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(matrix);
        return event;
    }

    // ------------------------------------------------------------------
    // 本命: ArsPaper 自身のレシピは TF に潰されない
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ArsPaper 所有レシピ + ArsPaper 品だけの盤面なら TF は結果枠に触らない")
    void arsPaperOwnedRecipeKeepsItsResultSlot() {
        PrepareItemCraftEvent event = prepareEvent(arsSourceJarRecipe(), sourceJarGrid());

        listener.onPrepareCraft(event);

        // ここが D5 の回帰ガード。従来は setResult(null) でソースジャーが永久に作れなかった。
        verify(event.getInventory(), never()).setResult(any());
    }

    @Test
    @DisplayName("ArsPaper 所有レシピの結果は取り出せる(onCraftItem がキャンセルしない)")
    void arsPaperOwnedRecipeCanBeTakenOutOfTheResultSlot() {
        CraftItemEvent event = craftEvent(arsSourceJarRecipe(), sourceJarGrid());

        listener.onCraftItem(event);

        // onCraftItem は HIGHEST なので、ここを直し忘れると「結果枠は出るが取り出せない」になる。
        verify(event, never()).setCancelled(true);
    }

    @Test
    @DisplayName("Crafter で ArsPaper 所有レシピを回してもキャンセルされない")
    void arsPaperOwnedRecipeIsNotCancelledInACrafter() {
        CrafterCraftEvent event = crafterEvent(arsSourceJarRecipe(), sourceJarGrid());

        listener.onCrafterCraft(event);

        verify(event, never()).setCancelled(true);
        verify(event, never()).setResult(any());
    }

    // ------------------------------------------------------------------
    // 緩めすぎていない証拠
    // ------------------------------------------------------------------

    @Test
    @DisplayName("案A の根拠: バニラレシピは従来どおり ArsPaper の圧縮品を溶かせない")
    void vanillaRecipeStillCannotConsumeAnArsPaperCompressedItem() {
        // 単純版 (TF 所有分だけ見る) に緩めるとここが通ってしまい、圧縮鉄ブロックが
        // バニラの分解レシピで鉄9個に溶ける。namespace 一致を要求する理由そのもの。
        PrepareItemCraftEvent event = prepareEvent(vanillaIronIngotRecipe(),
                singleItemGrid(withCustomModelData(Material.IRON_BLOCK, IRON_BLOCK_1X_CMD)));

        listener.onPrepareCraft(event);

        verify(event.getInventory()).setResult(null);
    }

    @Test
    @DisplayName("案A の根拠: バニラレシピで ArsPaper 品を取り出そうとしてもキャンセルされる")
    void vanillaRecipeTakeOutWithAnArsPaperItemIsStillCancelled() {
        CraftItemEvent event = craftEvent(vanillaIronIngotRecipe(),
                singleItemGrid(withCustomModelData(Material.IRON_BLOCK, IRON_BLOCK_1X_CMD)));

        listener.onCraftItem(event);

        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("案A の根拠: Crafter でもバニラレシピは ArsPaper 品を溶かせない")
    void vanillaRecipeInACrafterStillCannotConsumeAnArsPaperItem() {
        CrafterCraftEvent event = crafterEvent(vanillaIronIngotRecipe(),
                singleItemGrid(withCustomModelData(Material.IRON_BLOCK, IRON_BLOCK_1X_CMD)));

        listener.onCrafterCraft(event);

        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("TF 所有のカタログ品が混ざる盤面は他人のレシピから従来どおり守られる")
    void tfOwnedCatalogItemInTheGridStillBlocksAForeignRecipe() {
        // ArsPaper のレシピが選ばれていても、TF カタログ品(PDC catalog_id 付き)が乗っているなら
        // 委譲してはいけない — 圧縮ブロックの tier 誤爆(over-match)を再び開けないため。
        ItemStack[] matrix = sourceJarGrid();
        matrix[0] = tfCatalogItem(Material.BRICK, 4242, "tf_core_alpha");
        PrepareItemCraftEvent event = prepareEvent(arsSourceJarRecipe(), matrix);

        listener.onPrepareCraft(event);

        verify(event.getInventory()).setResult(null);
    }

    @Test
    @DisplayName("別プラグインの外部品が混ざる盤面も従来どおり守られる")
    void anotherPluginsExternalItemInTheGridStillBlocksTheArsPaperRecipe() {
        ExternalItemRegistry.updateExternalPlugin("otherplugin", Map.of("moon_dust",
                new ExternalItemRegistry.Definition("moon_dust", Material.SUGAR, 500, "月の砂")));
        ItemStack[] matrix = sourceJarGrid();
        matrix[0] = withCustomModelData(Material.SUGAR, 500);
        PrepareItemCraftEvent event = prepareEvent(arsSourceJarRecipe(), matrix);

        listener.onPrepareCraft(event);

        verify(event.getInventory()).setResult(null);
    }

    @Test
    @DisplayName("委譲は外部品が実際に乗っているときだけ(素のバニラ盤面では従来経路のまま)")
    void delegationRequiresAnActualForeignItemOnTheGrid() {
        // 盤面に CMD 付きの品が1つも無ければ gridHasCatalogItem が false なので、そもそも
        // このリスナーは介入しない。委譲判定が「外部品ゼロでも true」に化けていないことの確認。
        ItemStack[] plain = new ItemStack[9];
        plain[0] = new ItemStack(Material.IRON_BLOCK);
        PrepareItemCraftEvent event = prepareEvent(vanillaIronIngotRecipe(), plain);

        listener.onPrepareCraft(event);

        verify(event.getInventory(), never()).setResult(any());
    }
}
