package com.trinityforge.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trinityforge.pdc.BindType;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * スミス台(ネザライト強化)を統合版へ渡す表に載せる部分を固定する。
 *
 * <p>統合版の鍛冶台は base スロットに置けるかどうかを<b>クライアントが item tag
 * ({@code minecraft:transformable_items})で判定し</b>、結果もクライアントが計算する。
 * だからこの表の base に<b>カタログ品の CustomModelData</b>が載っていないと、
 * 補正レシピは素のバニラ装備を指してしまい「見た目は直ったのに実物は作れない」になる。
 *
 * <p>もうひとつ守るのがスロットの<b>並び</b>。テンプレ/base/追加素材は固定 3 枠で、
 * 入れ替わっても JSON の形は変わらないので<b>テストでしか検出できない</b>。
 */
class BedrockRecipeExporterSmithingTest {

    private static final int BOW_CMD = 100_501;
    private static final int UPGRADED_BOW_CMD = 100_502;

    private static ItemTemplate template(String id, Material material, Integer cmd) {
        return new ItemTemplate(id, material, "<white>" + id + "</white>", cmd, BindType.TRADEABLE, 0, null);
    }

    private static CatalogRecipeRegistrar.RegisteredRecipe entry(String id, ItemTemplate template, RecipeSpec spec) {
        return new CatalogRecipeRegistrar.RegisteredRecipe(
                NamespacedKey.fromString("trinityforge:catalog_" + id + "_smithing"), template, spec);
    }

    private static BedrockRecipeExporter.CustomItemResolver resolver(
            Map<String, BedrockRecipeTable.ItemRef> known) {
        return id -> Optional.ofNullable(known.get(id));
    }

    private static BedrockRecipeTable.Table build(
            List<CatalogRecipeRegistrar.RegisteredRecipe> smithing,
            BedrockRecipeExporter.CustomItemResolver resolver) {
        return BedrockRecipeExporter.build(List.of(), smithing, resolver);
    }

    /**
     * <b>本題。</b> base に source-item の CustomModelData が載り、テンプレと追加素材は
     * 登録側と同じバニラ材質になること。
     */
    @Test
    void putsTheSourceItemIdentityInTheBaseSlot() {
        BedrockRecipeTable.Table table = build(
                List.of(entry("star_bow", template("star_bow", Material.BOW, UPGRADED_BOW_CMD),
                        RecipeSpec.netherite("guard_bow", 1))),
                resolver(Map.of("guard_bow", BedrockRecipeTable.ItemRef.of(Material.BOW, BOW_CMD))));

        assertEquals(1, table.recipes().size(), "スミス台レシピが1件出るはず: " + table.skipped());
        BedrockRecipeTable.Recipe recipe = table.recipes().get(0);
        assertEquals(BedrockRecipeTable.Type.SMITHING, recipe.type());
        assertEquals(BedrockRecipeTable.SMITHING_SLOT_COUNT, recipe.slots().size());

        assertEquals(
                List.of(BedrockRecipeTable.ItemRef.of(
                        CatalogRecipeRegistrar.SMITHING_TEMPLATE_MATERIAL, null)),
                recipe.slots().get(BedrockRecipeTable.SMITHING_TEMPLATE_SLOT).items(),
                "テンプレ枠は登録側と同じバニラのネザライト強化テンプレでなければならない");
        assertEquals(
                List.of(BedrockRecipeTable.ItemRef.of(Material.BOW, BOW_CMD)),
                recipe.slots().get(BedrockRecipeTable.SMITHING_BASE_SLOT).items(),
                "base は source-item の material + CMD。CMD が落ちると素のバニラ弓を指してしまう");
        assertEquals(
                List.of(BedrockRecipeTable.ItemRef.of(
                        CatalogRecipeRegistrar.SMITHING_ADDITION_MATERIAL, null)),
                recipe.slots().get(BedrockRecipeTable.SMITHING_ADDITION_SLOT).items(),
                "追加素材枠は登録側と同じバニラのネザライトインゴット");

        assertEquals(Material.BOW, recipe.result().material());
        assertEquals(UPGRADED_BOW_CMD, recipe.result().customModelData());
        assertTrue(recipe.needsBedrockFix());
    }

    /**
     * source-item が解決できないレシピは<b>黙って消さず skipped に積む</b>。
     * 黙って落とすと「表は出ているのに直らない」原因が最後まで見えない。
     */
    @Test
    void anUnresolvableSourceItemIsReportedAsSkipped() {
        BedrockRecipeTable.Table table = build(
                List.of(entry("star_bow", template("star_bow", Material.BOW, UPGRADED_BOW_CMD),
                        RecipeSpec.netherite("does_not_exist", 1))),
                resolver(Map.of()));

        assertTrue(table.recipes().isEmpty());
        assertEquals(List.of("trinityforge:catalog_star_bow_smithing"), table.skipped());
    }

    /**
     * base がカスタム識別を持たない(＝素のバニラ材質)なら書き出さない。
     * Geyser の既定変換でそのまま正しく照合できるので、足すと同じレシピが二重に載るだけ。
     */
    @Test
    void aVanillaBaseIsLeftToGeysersOwnTranslation() {
        BedrockRecipeTable.Table table = build(
                List.of(entry("star_bow", template("star_bow", Material.BOW, UPGRADED_BOW_CMD),
                        RecipeSpec.netherite("plain_bow", 1))),
                resolver(Map.of("plain_bow", BedrockRecipeTable.ItemRef.of(Material.BOW, null))));

        assertTrue(table.recipes().isEmpty(), "バニラ base の補正は不要");
        assertTrue(table.skipped().isEmpty(), "解決はできているので skipped でもない");
    }

    /** JSON の形。{@code type} が新しい値になり、スロットが 3 枠で出ること。 */
    @Test
    void writesSmithingTypeAndThreeSlots() {
        JsonObject json = BedrockRecipeExporter.toJson(build(
                List.of(entry("star_bow", template("star_bow", Material.BOW, UPGRADED_BOW_CMD),
                        RecipeSpec.netherite("guard_bow", 1))),
                resolver(Map.of("guard_bow", BedrockRecipeTable.ItemRef.of(Material.BOW, BOW_CMD)))));

        assertEquals(BedrockRecipeTable.FORMAT_VERSION, json.get("version").getAsInt());
        JsonObject recipe = json.getAsJsonArray("recipes").get(0).getAsJsonObject();
        assertEquals("smithing", recipe.get("type").getAsString());
        JsonArray slots = recipe.getAsJsonArray("slots");
        assertEquals(3, slots.size());
        assertEquals(BOW_CMD, slots.get(BedrockRecipeTable.SMITHING_BASE_SLOT)
                .getAsJsonArray().get(0).getAsJsonObject().get("cmd").getAsInt());
    }

    /**
     * 形式バージョンを 2 未満へ戻すと、v1 の受け取り側がスミス台レシピを
     * 「3素材の作業台 shapeless レシピ」として統合版へ配ってしまう。
     * 下げられないように固定する。
     */
    @Test
    void formatVersionIsAtLeastTwoBecauseSmithingExists() {
        assertTrue(BedrockRecipeTable.FORMAT_VERSION >= 2,
                "SMITHING を持つ表は v1 の受け取り側に読ませてはいけない");
    }

    /** 3 枠でないスミス台レシピは組み立て時点で弾く(受け取り側まで運ばない)。 */
    @Test
    void aSmithingRecipeWithTheWrongSlotCountIsRejected() {
        BedrockRecipeTable.ItemRef any = BedrockRecipeTable.ItemRef.of(Material.BOW, BOW_CMD);
        assertThrows(IllegalArgumentException.class, () -> new BedrockRecipeTable.Recipe(
                "x", BedrockRecipeTable.Type.SMITHING, 0, 0,
                List.of(BedrockRecipeTable.Slot.of(any), BedrockRecipeTable.Slot.of(any)), any));
    }

    /** 空枠を含むスミス台レシピも弾く(統合版の UI は 3 枠すべてを要求する)。 */
    @Test
    void aSmithingRecipeWithAnEmptySlotIsRejected() {
        BedrockRecipeTable.ItemRef any = BedrockRecipeTable.ItemRef.of(Material.BOW, BOW_CMD);
        assertThrows(IllegalArgumentException.class, () -> new BedrockRecipeTable.Recipe(
                "x", BedrockRecipeTable.Type.SMITHING, 0, 0,
                List.of(BedrockRecipeTable.Slot.of(any), BedrockRecipeTable.Slot.empty(),
                        BedrockRecipeTable.Slot.of(any)), any));
    }
}
