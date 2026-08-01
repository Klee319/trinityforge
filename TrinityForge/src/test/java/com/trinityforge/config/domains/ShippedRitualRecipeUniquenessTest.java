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

    @Test
    @DisplayName("儀式レシピは (core-item, pedestal-items) が一意である(findFirst のため重複は永久に成立しない)")
    void ritualRecipesAreUniqueByCoreAndPedestal() {
        ConfigurationSection items = loadCatalogItems();

        // キー -> そのキーを持つカタログID（登録順）。yml の記述順がそのまま登録順になる。
        Map<String, List<String>> byIngredients = new LinkedHashMap<>();
        int ritualCount = 0;

        for (String itemId : items.getKeys(false)) {
            ConfigurationSection recipe = items.getConfigurationSection(itemId + ".recipe");
            if (recipe == null || !"ritual".equalsIgnoreCase(recipe.getString("method", ""))) {
                continue;
            }
            ritualCount++;
            byIngredients.computeIfAbsent(ingredientKey(recipe), key -> new ArrayList<>()).add(itemId);
        }

        assertTrue(ritualCount >= MIN_EXPECTED_RITUAL_RECIPES,
                "出荷カタログの儀式レシピが " + ritualCount + " 件しかない。"
                        + "節ごと消えていないか確認すること(期待: " + MIN_EXPECTED_RITUAL_RECIPES + " 件以上)");

        Map<String, List<String>> collisions = new TreeMap<>();
        byIngredients.forEach((key, ids) -> {
            if (ids.size() > 1) {
                collisions.put(key, ids);
            }
        });

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
     * {@code findMatch} が一致判定に使う情報だけからキーを作る。
     * pedestal は<b>並び順に依存しない</b>ので正規化してから連結する
     * （祭壇に置く順番はプレイヤーが決めるもので、レシピの同一性とは無関係）。
     */
    private static String ingredientKey(ConfigurationSection recipe) {
        String core = String.valueOf(recipe.getString("core-item", "")).trim().toLowerCase(Locale.ROOT);
        List<String> pedestal = new ArrayList<>();
        for (String raw : recipe.getStringList("pedestal-items")) {
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
}
