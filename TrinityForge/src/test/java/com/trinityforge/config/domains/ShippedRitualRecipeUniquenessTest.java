package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code items/catalog.yml} の儀式レシピが
 * <b>{@code (core-item, pedestal-items)} の組で一意である</b>ことを固定する（2026-08-01）。
 *
 * <p><b>なぜ一意でなければならないか（実装事実）</b>:
 * {@code com.trinityforge.progression.ritual.RitualRecipeRegistry#findMatch} は
 * 登録済みレシピを {@link java.util.LinkedHashMap} の順で走査し
 * <b>{@code findFirst} で最初に一致したものを返す</b>。したがって core-item と
 * pedestal-items が完全に一致するレシピが複数あると、<b>最初に登録された1本しか
 * 永久に成立しない</b>。残りは祭壇に正しく素材を並べても何も起きず、
 * <b>警告もエラーも出ない</b>（レシピ帳やエディタには載り続けるので、
 * 「書いたはずなのに作れない」という形でしか気づけない）。
 *
 * <p><b>実際に踏んだ規模</b>: 2026-08-01 のレビューで機械照合したところ、
 * 儀式レシピ119件のうち<b>13グループ・43件が永久にクラフト不可</b>だった
 * （abyss 武器12件 / binder 武器12件 / binder・dragon・wither 防具が各3件 /
 * hero・nuclear 系の同型武器）。例えば {@code abyss_sword} と {@code abyss_bow} は
 * core / pedestal / source がすべて同一だった。
 * 同日、武器種・部位ごとに素材を差別化して全件を到達可能にしてある。
 *
 * <p>このテストは source（消費ソース量）を<b>キーに含めない</b>。
 * {@code findMatch} が見ているのは core と pedestal だけで、source が違っても
 * 一致判定は変わらないため——source で差を付けても衝突は解けない。
 */
class ShippedRitualRecipeUniquenessTest {

    private static final String CATALOG = "src/main/resources/items/catalog.yml";

    /** 出荷カタログ内の儀式レシピ本数。極端に減った（＝節ごと消えた）ことに気づくための下限。 */
    private static final int MIN_EXPECTED_RITUAL_RECIPES = 100;

    /**
     * 走査結果。{@code byIngredients} は「素材キー -> そのキーを持つレシピの表示名（登録順）」。
     * yml の記述順がそのまま登録順になる。
     */
    private record Scan(int ritualCount, Map<String, List<String>> byIngredients) {
    }

    @Test
    @DisplayName("儀式レシピは (core-item, pedestal-items) が一意である(findFirst のため重複は永久に成立しない)")
    void ritualRecipesAreUniqueByCoreAndPedestal() {
        Scan scan = scan(loadCatalogItems());

        assertTrue(scan.ritualCount() >= MIN_EXPECTED_RITUAL_RECIPES,
                "出荷カタログの儀式レシピが " + scan.ritualCount() + " 件しかない。"
                        + "節ごと消えていないか確認すること(期待: " + MIN_EXPECTED_RITUAL_RECIPES + " 件以上)");

        Map<String, List<String>> collisions = collisions(scan);

        if (collisions.isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder();
        int unreachable = 0;
        for (Map.Entry<String, List<String>> entry : collisions.entrySet()) {
            List<String> ids = entry.getValue();
            unreachable += ids.size() - 1;
            message.append("\n  素材構成: ").append(entry.getKey())
                    .append("\n    成立するのは '").append(ids.get(0)).append("' だけ")
                    .append("\n    永久にクラフト不可: ").append(String.join(", ", ids.subList(1, ids.size())));
        }

        throw new AssertionError(
                "儀式レシピの素材構成が重複している(" + collisions.size() + "グループ / "
                        + unreachable + "件が永久にクラフト不可)。"
                        + "RitualRecipeRegistry#findMatch は findFirst なので、core-item と pedestal-items が"
                        + "完全一致するレシピは最初に登録された1本しか成立しない。"
                        + "武器種・部位ごとに素材か個数を変えて差別化すること"
                        + "(source を変えても findMatch は見ていないので解決しない)。"
                        + message);
    }

    /**
     * 走査が {@code recipes:}（複数形）側の儀式も拾うことを、出荷 yml に触らず fixture で固定する。
     *
     * <p><b>なぜ要るか</b>: 正規形は「0件=なし / 1件=<code>recipe:</code> / 2件以上=<code>recipes:</code>」で
     * 全 config 共通。儀式を2本持つアイテムは <b><code>recipe:</code> 節を持たない</b>ので、
     * 単数しか見ない走査はそのアイテムを<b>丸ごと取りこぼす</b>。すると
     * {@code RitualRecipeRegistry#findMatch}（findFirst）で永久に成立しない重複が
     * 無検査のまま出荷される。出荷カタログには今のところ {@code recipes:} が0件なので fixture で固定する。
     */
    @Test
    @DisplayName("recipes:(複数形)側の儀式も重複検査に入る — 単数 recipe: しか見ないとアイテムごと素通りする")
    void ritualsUnderTheRecipesListAreChecked() throws Exception {
        Scan scan = scan(fixtureItems());

        assertEquals(3, scan.ritualCount(),
                "recipes: に並ぶ儀式が走査されていない(単数 recipe: しか見ていない)。"
                        + "正規形では2本以上のレシピは recipes: にしか書かれないので、"
                        + "このままだと重複検査から丸ごと外れる。走査結果: " + scan.byIngredients());

        Map<String, List<String>> collisions = collisions(scan);
        assertEquals(1, collisions.size(),
                "core-item と pedestal-items が完全一致する儀式の重複が検出されていない。"
                        + "findFirst のため最初の1本以外は永久にクラフト不可になる。検出結果: " + collisions);
        assertEquals(List.of("fixture_alpha recipes[0]", "fixture_beta recipes[1]"),
                collisions.values().iterator().next(),
                "重複の検出元（アイテムID と recipes の添字）が正しく並んでいない。"
                        + "pedestal の並び順は findMatch の一致判定に影響しないので、"
                        + "順番違いも同一構成として検出されなければならない。検出結果: " + collisions);
    }

    // ------------------------------------------------------------------------------------------
    // 走査
    // ------------------------------------------------------------------------------------------

    /** {@code items:} 節の儀式レシピを全て走査し、素材キーごとにまとめる。 */
    private static Scan scan(ConfigurationSection items) {
        Map<String, List<String>> byIngredients = new LinkedHashMap<>();
        int ritualCount = 0;

        for (String itemId : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(itemId);
            if (item == null) {
                continue;
            }
            for (RitualRecipe ritual : ritualRecipes(itemId, item)) {
                ritualCount++;
                byIngredients.computeIfAbsent(
                        ingredientKey(ritual.coreItem(), ritual.pedestalItems()),
                        key -> new ArrayList<>()).add(ritual.label());
            }
        }
        return new Scan(ritualCount, byIngredients);
    }

    /** 走査対象の儀式1本。{@code label} は失敗メッセージで出所を特定するための表示名。 */
    private record RitualRecipe(String label, String coreItem, List<String> pedestalItems) {
    }

    /**
     * 1アイテムぶんの儀式レシピを、<b>{@code recipe:}（単数）と {@code recipes:}（複数）の両方</b>から拾う。
     *
     * <p>正規形は「0件=なし / 1件=<code>recipe:</code> / 2件以上=<code>recipes:</code>」で全 config 共通
     * （{@link ItemCatalogConfig#parse} も両方を読んで1本のリストに積み、どちらも同じ登録経路へ流す）。
     * 単数だけを見ると、レシピを2本持つアイテムが<b>丸ごと重複検査から外れる</b>。
     */
    private static List<RitualRecipe> ritualRecipes(String itemId, ConfigurationSection item) {
        List<RitualRecipe> out = new ArrayList<>();

        ConfigurationSection single = item.getConfigurationSection("recipe");
        if (single != null && isRitual(single.getString("method"))) {
            out.add(new RitualRecipe(itemId, single.getString("core-item"),
                    single.getStringList("pedestal-items")));
        }

        List<Map<?, ?>> list = item.getMapList("recipes");
        for (int i = 0; i < list.size(); i++) {
            Map<?, ?> raw = list.get(i);
            if (raw == null || !isRitual(text(raw.get("method")))) {
                continue;
            }
            out.add(new RitualRecipe(itemId + " recipes[" + i + "]",
                    text(raw.get("core-item")), strings(raw.get("pedestal-items"))));
        }
        return out;
    }

    private static boolean isRitual(String method) {
        return method != null && "ritual".equalsIgnoreCase(method.trim());
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element != null) {
                    out.add(String.valueOf(element));
                }
            }
        }
        return out;
    }

    /** 同じ素材キーを2本以上が共有しているものだけを取り出す。 */
    private static Map<String, List<String>> collisions(Scan scan) {
        Map<String, List<String>> collisions = new TreeMap<>();
        scan.byIngredients().forEach((key, ids) -> {
            if (ids.size() > 1) {
                collisions.put(key, ids);
            }
        });
        return collisions;
    }

    /**
     * {@code findMatch} が一致判定に使う情報だけからキーを作る。
     * pedestal は<b>並び順に依存しない</b>ので正規化してから連結する
     * （祭壇に置く順番はプレイヤーが決めるもので、レシピの同一性とは無関係）。
     */
    private static String ingredientKey(String coreItem, List<String> pedestalItems) {
        String core = String.valueOf(coreItem == null ? "" : coreItem).trim().toLowerCase(Locale.ROOT);
        List<String> pedestal = new ArrayList<>();
        for (String raw : pedestalItems) {
            if (raw != null && !raw.isBlank()) {
                pedestal.add(raw.trim().toLowerCase(Locale.ROOT));
            }
        }
        pedestal.sort(String::compareTo);
        return "core=" + core + " pedestal=" + pedestal;
    }

    private static ConfigurationSection loadCatalogItems() {
        File file = new File(CATALOG);
        assertTrue(file.isFile(), "出荷カタログが見つからない: " + file.getAbsolutePath());
        ConfigurationSection items = YamlConfiguration.loadConfiguration(file).getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");
        return items;
    }

    /**
     * 正規形どおりに {@code recipes:} だけでレシピを並べた fixture。出荷 yml は一切触らない。
     * {@code fixture_alpha recipes[0]} と {@code fixture_beta recipes[1]} が
     * （並び順違いで）同一の素材構成になっており、非儀式と単独構成の儀式も混ぜてある。
     */
    private static ConfigurationSection fixtureItems() throws Exception {
        YamlConfiguration fixture = new YamlConfiguration();
        fixture.loadFromString(String.join("\n",
                "items:",
                "  fixture_alpha:",
                "    material: IRON_SWORD",
                "    recipes:",
                "      - method: ritual",
                "        core-item: IRON_INGOT",
                "        pedestal-items:",
                "          - \"custom:fixture_a x2\"",
                "          - \"custom:fixture_b\"",
                "      - method: workbench",
                "        type: shapeless",
                "        ingredients:",
                "          - IRON_INGOT",
                "  fixture_beta:",
                "    material: IRON_AXE",
                "    recipes:",
                "      - method: ritual",
                "        core-item: GOLD_INGOT",
                "        pedestal-items:",
                "          - \"custom:fixture_c\"",
                "      - method: ritual",
                "        core-item: IRON_INGOT",
                "        pedestal-items:",
                "          - \"custom:fixture_b\"",
                "          - \"custom:fixture_a x2\"",
                ""));
        ConfigurationSection items = fixture.getConfigurationSection("items");
        assertNotNull(items, "fixture の items: 節が読めていない");
        return items;
    }
}
