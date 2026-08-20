package com.trinityforge.bedrock;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.MaterialListsConfig;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code items/catalog.yml} に対して補正レシピ表が<b>実際に中身を持つ</b>ことを確かめる。
 *
 * <p>単体テストは「与えた spec を正しく写せるか」しか見ていない。それだけだと
 * <b>出荷カタログでは 1 件も出ない</b>（＝統合版のクラフトは何も直らない）状態でも全部緑になる。
 * この機能は「効いていないこと」が症状として見えないので、出荷データでの下限をここで踏む。
 *
 * <p><b>ArsPaper は起動していない</b>ので、Ars 実体の {@code custom:} 素材（圧縮素材など）は
 * ここでは解決できず見送られる。だから件数は本番より必ず少なくなる ──
 * 下限はその前提で置いてある。
 */
class ShippedBedrockRecipeTableTest {

    private static final String CATALOG = "src/main/resources/items/catalog.yml";
    private static final String MATERIAL_LISTS = "src/main/resources/items/material-lists.yml";

    /**
     * 補正の対象になりうるレシピ（＝{@code custom:} / {@code list:} 素材を使うもの）の下限。
     *
     * <p><b>「書き出せた件数」ではなく「対象として見えた件数」で踏む。</b>
     * このテストでは ArsPaper が起動していないので、Ars 実体の {@code custom:} 素材
     * （ソースジェム・圧縮素材など）は解決できず {@code skipped} に落ちる ──
     * 2026-08-20 の実測で対象 73 件のうち 71 件がそれだった。実サーバでは
     * {@code refreshCatalogRecipes()}（ArsPaper の enable 後）で解決される。
     *
     * <p>実測値より十分低く置いている。狙いは「節ごと消えた／エクスポータが対象を
     * 1 件も拾わなくなった」ことに気づくこと。
     */
    private static final int MINIMUM_CANDIDATES = 50;

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedBedrockRecipeTableTest");
            case "saveResource" -> throw new AssertionError("既にファイルがあるのに saveResource が呼ばれた");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    /** {@code list:} 素材を解決できるようにする。読み込まないと list を使うレシピが全部 skipped になる。 */
    private static void loadShippedMaterialLists() throws Exception {
        java.nio.file.Path source = java.nio.file.Path.of(MATERIAL_LISTS);
        assertTrue(Files.isRegularFile(source), "出荷 material-lists.yml が見つからない: " + source.toAbsolutePath());
        File dataFolder = Files.createTempDirectory("bedrock-lists").toFile();
        File target = new File(dataFolder, MaterialListsConfig.PATH);
        Files.createDirectories(target.getParentFile().toPath());
        Files.copy(source, target.toPath());
        new MaterialListsConfig().load(fakePlugin(dataFolder));
    }

    private static ItemCatalogConfig loadShipped() throws Exception {
        loadShippedMaterialLists();
        java.nio.file.Path source = java.nio.file.Path.of(CATALOG);
        assertTrue(Files.isRegularFile(source), "出荷カタログが見つからない: " + source.toAbsolutePath());
        File dataFolder = Files.createTempDirectory("bedrock-recipes").toFile();
        File target = new File(dataFolder, ItemCatalogConfig.PATH);
        Files.createDirectories(target.getParentFile().toPath());
        Files.copy(source, target.toPath());
        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(dataFolder));
        return config;
    }

    /**
     * {@code CatalogRecipeRegistrar#registerAll()} は Bukkit のレシピ登録を伴うので呼べない。
     * 登録対象の作り方（テンプレート×宣言レシピ、2 本目以降のキーは {@code _<n>}）だけを写す。
     */
    private static List<CatalogRecipeRegistrar.RegisteredRecipe> entriesOf(ItemCatalogConfig catalog) {
        List<CatalogRecipeRegistrar.RegisteredRecipe> entries = new ArrayList<>();
        for (Map.Entry<String, ItemTemplate> item : catalog.all().entrySet()) {
            int index = 0;
            for (RecipeSpec spec : item.getValue().recipes()) {
                if (!spec.isBukkitCrafting()) {
                    continue;
                }
                index++;
                String keyName = index == 1 ? "catalog_" + item.getKey() : "catalog_" + item.getKey() + "_" + index;
                entries.add(new CatalogRecipeRegistrar.RegisteredRecipe(
                        NamespacedKey.fromString("trinityforge:" + keyName), item.getValue(), spec));
            }
        }
        return entries;
    }

    @Test
    void shippedCatalogProducesABedrockRecipeTable() throws Exception {
        ItemCatalogConfig catalog = loadShipped();
        BedrockRecipeTable.Table table = BedrockRecipeExporter.build(entriesOf(catalog), catalog);

        int candidates = table.recipes().size() + table.skipped().size();
        assertTrue(candidates >= MINIMUM_CANDIDATES,
                "統合版向け補正レシピ表が出荷カタログで対象をほとんど拾えていない。"
                        + "この機能は効いていなくても症状が『統合版でクラフトできない』のままなので"
                        + "気づけない。対象=" + candidates
                        + " (書き出し=" + table.recipes().size()
                        + " / 素材未解決=" + table.skipped().size() + ")");
        assertFalse(table.recipes().isEmpty(),
                "TF カタログ内で完結する custom: 素材のレシピが 1 件も書き出せていない。"
                        + "解決器かカタログ参照が壊れている疑いがある。見送り=" + table.skipped().size());
    }

    /** 書き出した表の素材は必ず 1 つ以上カスタム識別を持つ（持たないなら足す意味が無い）。 */
    @Test
    void everyExportedRecipeHasACustomIngredient() throws Exception {
        ItemCatalogConfig catalog = loadShipped();
        BedrockRecipeTable.Table table = BedrockRecipeExporter.build(entriesOf(catalog), catalog);

        List<String> pointless = table.recipes().stream()
                .filter(recipe -> !recipe.needsBedrockFix())
                .map(BedrockRecipeTable.Recipe::id)
                .toList();
        assertTrue(pointless.isEmpty(),
                "素材が全部バニラのレシピを書き出している。Geyser の既定変換と重複するだけ: " + pointless);
    }

    /** shaped の盤面は必ず {@code width * height} 個のスロットを持つ（受け取り側が行優先で読む）。 */
    @Test
    void shapedRecipesCarryAFullGrid() throws Exception {
        ItemCatalogConfig catalog = loadShipped();
        BedrockRecipeTable.Table table = BedrockRecipeExporter.build(entriesOf(catalog), catalog);

        List<String> broken = table.recipes().stream()
                .filter(recipe -> recipe.type() == BedrockRecipeTable.Type.SHAPED)
                .filter(recipe -> recipe.slots().size() != recipe.width() * recipe.height())
                .map(BedrockRecipeTable.Recipe::id)
                .toList();
        assertTrue(broken.isEmpty(), "盤面のマス数が合っていない: " + broken);
        assertFalse(table.recipes().isEmpty());
    }
}
