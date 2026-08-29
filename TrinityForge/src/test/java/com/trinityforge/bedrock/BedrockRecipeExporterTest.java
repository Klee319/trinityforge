package com.trinityforge.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trinityforge.pdc.BindType;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.MaterialLists;
import com.trinityforge.stats.RecipeIngredient;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 統合版へ渡す補正レシピ表の中身を固定する。
 *
 * <p>ここで守るのは「<b>素材の identity が落ちないこと</b>」の一点。
 * Geyser の既定変換が素材を CustomModelData ごと捨ててしまうことが不具合の真因なので、
 * この表から CMD が消えたら補正の意味が丸ごと無くなる ── しかも
 * <b>症状は「クラフトできない」のままで、表を出していることは見えてしまう</b>。
 */
class BedrockRecipeExporterTest {

    private static final Integer STONE_1X_CMD = 100_001;
    private static final Integer STONE_2X_CMD = 100_002;

    /**
     * 作業台レシピだけを渡す短縮形。スミス台側は {@link BedrockRecipeExporterSmithingTest} が見る。
     */
    private static BedrockRecipeTable.Table buildCrafting(
            List<CatalogRecipeRegistrar.RegisteredRecipe> registered,
            BedrockRecipeExporter.CustomItemResolver resolver) {
        return BedrockRecipeExporter.build(registered, List.of(), resolver);
    }

    /** テスト用の解決器。catalog.yml を用意せずに {@code custom:<id>} を解ける。 */
    private static BedrockRecipeExporter.CustomItemResolver resolver(
            Map<String, BedrockRecipeTable.ItemRef> known) {
        return id -> Optional.ofNullable(known.get(id));
    }

    private static ItemTemplate template(String id, Material material, Integer cmd) {
        return new ItemTemplate(id, material, "<white>" + id + "</white>", cmd, BindType.TRADEABLE, 0, null);
    }

    private static CatalogRecipeRegistrar.RegisteredRecipe entry(String id, ItemTemplate template, RecipeSpec spec) {
        return new CatalogRecipeRegistrar.RegisteredRecipe(
                NamespacedKey.fromString("trinityforge:catalog_" + id), template, spec);
    }

    /** 3×3 を同じ {@code custom:} 素材で埋める圧縮レシピ。今回の不具合の典型形。 */
    private static RecipeSpec compression(String ingredientId) {
        return RecipeSpec.shaped(
                List.of("iii", "iii", "iii"),
                Map.of('i', RecipeIngredient.ofCatalog(ingredientId)),
                1);
    }

    /**
     * 結果も解決できないエントリは<b>表から落ちるだけ</b>で、他のレシピを道連れにしないこと。
     *
     * <p>{@code template} が {@code null} なのは異常ではなく
     * {@code progression/crafting-features.yml} の {@code added-recipes} の正常形
     * ({@code fixedResult} を持つ)。<b>結果を持つ正常な added-recipes を通す回帰テストは
     * {@code CatalogRecipeRegistrarTest} 側</b> ── あちらは {@code fixedResult} に本物の
     * {@code ItemStack} が要るので MockBukkit の上に置いてある。
     */
    @Test
    void anEntryWithNeitherTemplateNorResultIsSkippedNotFatal() {
        CatalogRecipeRegistrar.RegisteredRecipe broken = new CatalogRecipeRegistrar.RegisteredRecipe(
                NamespacedKey.fromString("trinityforge:added_broken"),
                null,
                RecipeSpec.shaped(
                        List.of("ii", "ii"),
                        Map.of('i', RecipeIngredient.ofCatalog("stone_1x")),
                        1),
                null);

        BedrockRecipeTable.Table table = buildCrafting(
                List.of(broken,
                        entry("stone_2x", template("stone_2x", Material.STONE, STONE_2X_CMD),
                                compression("stone_1x"))),
                resolver(Map.of("stone_1x", BedrockRecipeTable.ItemRef.of(Material.STONE, STONE_1X_CMD))));

        assertEquals(1, table.recipes().size(), "健全な方は残る");
        assertTrue(table.skipped().contains("trinityforge:added_broken"),
                "落とした分は skipped に記録する: " + table.skipped());
    }

    /**
     * <b>本題。</b> 素材の CustomModelData がそのまま表に出ること。
     * ここが null になると受け取り側はバニラ素材の descriptor しか作れず、
     * Geyser の既定変換と同じ＝何も直らない表になる。
     */
    @Test
    void keepsCustomModelDataOfIngredients() {
        BedrockRecipeTable.Table table = buildCrafting(
                List.of(entry("stone_2x", template("stone_2x", Material.STONE, STONE_2X_CMD),
                        compression("stone_1x"))),
                resolver(Map.of("stone_1x", BedrockRecipeTable.ItemRef.of(Material.STONE, STONE_1X_CMD))));

        assertEquals(1, table.recipes().size(), "圧縮レシピが1件出るはず: " + table.skipped());
        BedrockRecipeTable.Recipe recipe = table.recipes().get(0);
        assertEquals(BedrockRecipeTable.Type.SHAPED, recipe.type());
        assertEquals(3, recipe.width());
        assertEquals(3, recipe.height());
        assertEquals(9, recipe.slots().size());
        for (BedrockRecipeTable.Slot slot : recipe.slots()) {
            assertEquals(List.of(BedrockRecipeTable.ItemRef.of(Material.STONE, STONE_1X_CMD)), slot.items());
        }
        assertEquals(new BedrockRecipeTable.ItemRef(Material.STONE, STONE_2X_CMD, 1), recipe.result());
    }

    /**
     * 素材が全部バニラのレシピは<b>書き出さない</b>。
     * Geyser の既定変換で正しく照合できる（結果側は元から正しく変換される）ので、
     * 足すと同じレシピが二重に載るだけになる。
     */
    @Test
    void skipsRecipesWhoseIngredientsAreAllVanilla() {
        RecipeSpec vanillaOnly = RecipeSpec.shaped(
                List.of("ii", "ii"),
                Map.of('i', RecipeIngredient.ofMaterial(Material.STONE)),
                1);

        BedrockRecipeTable.Table table = buildCrafting(
                List.of(entry("plain", template("plain", Material.STONE, 999), vanillaOnly)),
                resolver(Map.of()));

        assertTrue(table.recipes().isEmpty(), "バニラ素材だけのレシピは出さない");
        assertTrue(table.skipped().isEmpty(), "解決失敗ではないので skipped にも入らない");
    }

    /**
     * <b>解凍レシピの取りこぼし防止。</b> 逆レシピは {@code allRegistered()} に載らず
     * {@code registerReverseOne} が Bukkit へ直接入れるだけなので、spec から組み直す必要がある。
     * そして逆レシピの素材は必ずカスタム品（正レシピの完成品）なので、
     * ここを落とすと「解凍だけ直らない」という実サーバ報告そのものの状態になる。
     */
    @Test
    void emitsTheDecompressRecipeForReversibleEntries() {
        RecipeSpec reversible = compression("stone_1x").withReversible(true);

        BedrockRecipeTable.Table table = buildCrafting(
                List.of(entry("stone_2x", template("stone_2x", Material.STONE, STONE_2X_CMD), reversible)),
                resolver(Map.of("stone_1x", BedrockRecipeTable.ItemRef.of(Material.STONE, STONE_1X_CMD))));

        BedrockRecipeTable.Recipe decompress = table.recipes().stream()
                .filter(r -> r.id().endsWith("_decompress"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("解凍レシピが出ていない: " + table.recipes()));
        assertEquals("trinityforge:catalog_stone_2x_decompress", decompress.id());
        assertEquals(BedrockRecipeTable.Type.SHAPELESS, decompress.type());
        assertEquals(List.of(BedrockRecipeTable.ItemRef.of(Material.STONE, STONE_2X_CMD)),
                decompress.slots().get(0).items(), "素材は完成品そのもの");
        assertEquals(new BedrockRecipeTable.ItemRef(Material.STONE, STONE_1X_CMD, 9), decompress.result(),
                "結果は元素材9個");
    }

    /** {@code list:} 素材は1マスに複数候補として出す（組み合わせ展開は受け取り側の仕事）。 */
    @Test
    void listIngredientBecomesMultipleCandidatesInOneSlot() {
        MaterialLists.update(
                Map.of("test_metals", Set.of(Material.IRON_INGOT, Material.GOLD_INGOT)),
                Map.of("test_metals", Set.of("hard_metal")),
                Map.of("test_metals", "テスト金属"));
        try {
            RecipeSpec spec = RecipeSpec.shapeless(
                    List.of(RecipeIngredient.ofList("test_metals"), RecipeIngredient.ofCatalog("stone_1x")),
                    1);

            BedrockRecipeTable.Table table = buildCrafting(
                    List.of(entry("alloy", template("alloy", Material.IRON_BLOCK, 500), spec)),
                    resolver(Map.of(
                            "hard_metal", BedrockRecipeTable.ItemRef.of(Material.IRON_INGOT, 5001),
                            "stone_1x", BedrockRecipeTable.ItemRef.of(Material.STONE, STONE_1X_CMD))));

            assertEquals(1, table.recipes().size(), "skipped=" + table.skipped());
            BedrockRecipeTable.Slot listSlot = table.recipes().get(0).slots().get(0);
            assertEquals(List.of(
                            BedrockRecipeTable.ItemRef.of(Material.GOLD_INGOT, null),
                            BedrockRecipeTable.ItemRef.of(Material.IRON_INGOT, null),
                            BedrockRecipeTable.ItemRef.of(Material.IRON_INGOT, 5001)),
                    listSlot.items(),
                    "バニラ素材は名前順、そのあとカスタム品（並びが揺れると差分が毎回出る）");
        } finally {
            MaterialLists.update(Map.of(), Map.of(), Map.of());
        }
    }

    /**
     * 解決できない {@code custom:} 素材があるレシピは<b>丸ごと落として名前を残す</b>。
     * 半分だけ正しい表を出すと、受け取り側は誤った素材で照合するレシピを注入してしまい、
     * 「クラフトできない」より悪い「別のレシピが成立する」になる。
     */
    @Test
    void dropsAndReportsRecipesWithUnresolvableIngredients() {
        BedrockRecipeTable.Table table = buildCrafting(
                List.of(entry("stone_2x", template("stone_2x", Material.STONE, STONE_2X_CMD),
                        compression("does_not_exist"))),
                resolver(Map.of()));

        assertTrue(table.recipes().isEmpty());
        assertEquals(List.of("trinityforge:catalog_stone_2x"), table.skipped());
    }

    /** 儀式・鍛冶・合成は作業台の照合を通らないので対象外。 */
    @Test
    void ignoresNonWorkbenchMethods() {
        RecipeSpec ritual = RecipeSpec.ritual("LAPIS_BLOCK", List.of("AMETHYST_SHARD"), 4500, 1);
        RecipeSpec netherite = RecipeSpec.netherite("custom:stone_1x", 1);

        BedrockRecipeTable.Table table = buildCrafting(
                List.of(entry("r", template("r", Material.STONE, 1), ritual),
                        entry("n", template("n", Material.BOW, 2), netherite)),
                resolver(Map.of("stone_1x", BedrockRecipeTable.ItemRef.of(Material.STONE, STONE_1X_CMD))));

        assertTrue(table.recipes().isEmpty());
        assertTrue(table.skipped().isEmpty());
    }

    /**
     * JSON の形を固定する。受け取り側は別プロセス・別リポジトリなので、
     * ここが変わると<b>気づかないまま読み込みが空になる</b>。
     */
    @Test
    void jsonKeepsEmptySlotsAsNullAndOmitsCmdForVanilla() {
        RecipeSpec spec = RecipeSpec.shaped(
                List.of("i ", " v"),
                Map.of('i', RecipeIngredient.ofCatalog("stone_1x"),
                        'v', RecipeIngredient.ofMaterial(Material.STICK)),
                4);

        JsonObject json = BedrockRecipeExporter.toJson(buildCrafting(
                List.of(entry("thing", template("thing", Material.STONE, STONE_2X_CMD), spec)),
                resolver(Map.of("stone_1x", BedrockRecipeTable.ItemRef.of(Material.STONE, STONE_1X_CMD)))));

        assertEquals(BedrockRecipeTable.FORMAT_VERSION, json.get("version").getAsInt());
        assertEquals("TrinityForge", json.get("source").getAsString());
        JsonObject recipe = json.getAsJsonArray("recipes").get(0).getAsJsonObject();
        assertEquals("shaped", recipe.get("type").getAsString());
        assertEquals(2, recipe.get("width").getAsInt());
        assertEquals(2, recipe.get("height").getAsInt());

        JsonArray slots = recipe.getAsJsonArray("slots");
        assertEquals(4, slots.size());
        assertNotNull(slots.get(0).getAsJsonArray());
        assertTrue(slots.get(1).isJsonNull(), "空欄は null（行優先の位置がずれる）");
        assertTrue(slots.get(2).isJsonNull());
        JsonObject vanilla = slots.get(3).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("STICK", vanilla.get("material").getAsString());
        assertFalse(vanilla.has("cmd"), "バニラ素材に cmd は書かない");

        JsonObject custom = slots.get(0).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(STONE_1X_CMD.intValue(), custom.get("cmd").getAsInt());
        assertEquals(4, recipe.getAsJsonObject("result").get("count").getAsInt());
    }
}
