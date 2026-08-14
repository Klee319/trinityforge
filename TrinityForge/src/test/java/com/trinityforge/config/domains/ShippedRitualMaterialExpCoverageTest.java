package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code items/catalog.yml} の儀式レシピが消費する素材が
 * <b>1つ残らず {@code stats/skill-exp.yml} の {@code smithing.exp-per-material} に載っている</b>
 * ことを固定する（2026-08-02）。
 *
 * <p><b>なぜ全カバーが要るか（実装事実）</b>:
 * {@link com.trinityforge.integration.ars.ArsProgressionBridge#grantSmithingCraftExp} は
 * 消費素材を上の表で合計した値を鍛冶EXPにするが、<b>1つでも表に無い素材があれば</b>
 * 合計を信用せず {@code ars-smithing.exp-per-craft} の定額へ戻す。
 * よって行が欠けたレシピは「素材価値と無関係な定額」に落ちる——
 * <b>警告もエラーも出ないので、EXP を見比べない限り気づけない</b>。
 *
 * <p><b>実際に踏んだ規模</b>: 導入直後（2026-08-01）は表に儀式素材の行が1つも無く、
 * 当時の「合計が0のときだけ定額」という規則と噛み合って
 * <b>121件中57件が定額100より下へ落ちていた</b>。最悪の例が {@code binder_spear}
 * （source 60,000 の最上位武器なのに <b>1 EXP</b>）で、同格の {@code binder_sword} は
 * 全素材が表に無いおかげで 100 のまま——<b>安い素材を1つ足すとEXPが100分の1になる</b>
 * という向きの不整合だった。規則を「全カバーのときだけ合計」に変え、表を埋めて解消してある。
 *
 * <p>このテストは<b>値の妥当性は見ない</b>（バランスは設計判断なので固定しない）。
 * 見るのは「引けるかどうか」だけ。
 */
class ShippedRitualMaterialExpCoverageTest {

    private static final String CATALOG = "src/main/resources/items/catalog.yml";
    private static final String SKILL_EXP = "src/main/resources/stats/skill-exp.yml";

    /** 出荷カタログ内の儀式レシピ本数の下限。節ごと消えたことに気づくため。 */
    private static final int MIN_EXPECTED_RITUAL_RECIPES = 100;

    /** {@code "custom:hard_metal x4"} や {@code "IRON_INGOT"} から個数を切り離す。 */
    private static final Pattern TOKEN_WITH_COUNT = Pattern.compile("^(.*?)(?:\\s+x\\d+)?$");

    /**
     * 走査結果。{@code missing} は「表に無い素材トークン -> それを使っているレシピの表示名」。
     */
    private record Scan(int ritualCount, TreeMap<String, Set<String>> missing) {
    }

    @Test
    @DisplayName("儀式レシピの消費素材は全て smithing.exp-per-material に載っている(1つ欠けると定額へ落ちる)")
    void everyRitualMaterialHasAnExpRow() {
        Set<String> table = loadMaterialTableKeys();
        Scan scan = scan(table, loadCatalogItems());

        assertTrue(scan.ritualCount() >= MIN_EXPECTED_RITUAL_RECIPES,
                "出荷カタログの儀式レシピが " + scan.ritualCount() + " 件しかない。"
                        + "節ごと消えていないか確認すること(期待: " + MIN_EXPECTED_RITUAL_RECIPES + " 件以上)");

        if (scan.missing().isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder();
        Set<String> affected = new TreeSet<>();
        scan.missing().forEach((token, ids) -> {
            affected.addAll(ids);
            message.append("\n  ").append(token)
                    .append("  (").append(ids.size()).append("件で使用: ")
                    .append(String.join(", ", ids.stream().limit(4).toList()))
                    .append(ids.size() > 4 ? ", …" : "").append(")");
        });

        throw new AssertionError(
                "儀式で消費するのに smithing.exp-per-material に行が無い素材が "
                        + scan.missing().size() + " 種類ある(影響する儀式レシピ " + affected.size() + " 件)。"
                        + "ArsProgressionBridge#grantSmithingCraftExp は1つでも引けないと"
                        + "素材合計を捨てて ars-smithing.exp-per-craft の定額へ戻すので、"
                        + "これらのレシピは素材価値と無関係な固定EXPになる。"
                        + "skill-exp.yml に行を足すこと(値の目安はファイル内のコメント参照)。"
                        + message);
    }

    /**
     * 走査が {@code recipes:}（複数形）側の儀式も拾うことを、出荷 yml に触らず fixture で固定する。
     *
     * <p><b>なぜ要るか</b>: このリポジトリの正規形は「0件=なし / 1件=<code>recipe:</code> /
     * 2件以上=<code>recipes:</code>」で全 config 共通。つまり儀式を2本持つアイテムは
     * <b><code>recipe:</code> 節を持たず <code>recipes:</code> だけを持つ</b>。単数しか見ない走査は
     * その形を<b>アイテムごと丸ごと取りこぼす</b>ので、2本目を足した瞬間に上のテストが
     * 無言で素通りし、「素材が表に無い→鍛冶EXPが定額に落ちる」という検出目的が失われる。
     * 出荷カタログには今のところ {@code recipes:} が0件なので、fixture でしか固定できない。
     */
    @Test
    @DisplayName("recipes:(複数形)側の儀式も走査対象に入る — 単数 recipe: しか見ないとアイテムごと素通りする")
    void ritualsUnderTheRecipesListAreScanned() throws Exception {
        ConfigurationSection items = fixtureItems();

        Scan scan = scan(Set.of("IRON_INGOT", "custom:covered_material"), items);

        assertEquals(2, scan.ritualCount(),
                "recipes: に並ぶ儀式2本が走査されていない(単数 recipe: しか見ていない)。"
                        + "正規形では2本以上のレシピは recipes: にしか書かれないので、"
                        + "このままだと2本目を足したレシピが検査対象から丸ごと外れる");
        assertEquals(Set.of("GOLD_INGOT", "custom:uncovered_material"), scan.missing().keySet(),
                "recipes: 側の儀式が使う「表に無い素材」が検出されていない。"
                        + "検出されなければ ArsProgressionBridge が鍛冶EXPを定額へ落とすのに"
                        + "誰も気づけない。実際の検出結果: " + scan.missing());
    }

    // ------------------------------------------------------------------------------------------
    // 走査
    // ------------------------------------------------------------------------------------------

    /** {@code items:} 節の儀式レシピを全て走査し、表に無い素材を集める。 */
    private static Scan scan(Set<String> table, ConfigurationSection items) {
        TreeMap<String, Set<String>> missing = new TreeMap<>();
        int ritualCount = 0;

        for (String itemId : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(itemId);
            if (item == null) {
                continue;
            }
            for (RitualRecipe ritual : ritualRecipes(itemId, item)) {
                ritualCount++;
                for (String token : materialTokens(ritual.coreItem(), ritual.pedestalItems())) {
                    if (!table.contains(token)) {
                        missing.computeIfAbsent(token, key -> new TreeSet<>()).add(ritual.label());
                    }
                }
            }
        }
        return new Scan(ritualCount, missing);
    }

    /** 走査対象の儀式1本。{@code label} は失敗メッセージで出所を特定するための表示名。 */
    private record RitualRecipe(String label, String coreItem, List<String> pedestalItems) {
    }

    /**
     * 1アイテムぶんの儀式レシピを、<b>{@code recipe:}（単数）と {@code recipes:}（複数）の両方</b>から拾う。
     *
     * <p>正規形は「0件=なし / 1件=<code>recipe:</code> / 2件以上=<code>recipes:</code>」で全 config 共通
     * （{@link ItemCatalogConfig#parse} も両方を読んで1本のリストに積む）。
     * 単数だけを見ると、レシピを2本持つアイテムが<b>丸ごと検査対象から外れる</b>。
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

    /** core-item と pedestal-items を、個数を落とした正規化トークンで返す。 */
    private static List<String> materialTokens(String coreItem, List<String> pedestalItems) {
        List<String> raw = new ArrayList<>();
        if (coreItem != null && !coreItem.isBlank()) {
            raw.add(coreItem);
        }
        raw.addAll(pedestalItems);

        List<String> tokens = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Matcher matcher = TOKEN_WITH_COUNT.matcher(entry.trim());
            String token = matcher.matches() ? matcher.group(1).trim() : entry.trim();
            if (!token.isEmpty()) {
                tokens.add(normalize(token));
            }
        }
        return tokens;
    }

    /**
     * {@code SkillExpConfig#normalizeMaterialToken} と同じ規約:
     * {@code custom:} 接頭辞つきは ID をそのまま小文字扱い、それ以外は Material 名として大文字化。
     */
    private static String normalize(String token) {
        String trimmed = token.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("custom:")) {
            return "custom:" + trimmed.substring("custom:".length()).trim().toLowerCase(Locale.ROOT);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------------
    // 読み込み
    // ------------------------------------------------------------------------------------------

    private static Set<String> loadMaterialTableKeys() {
        File file = new File(SKILL_EXP);
        assertTrue(file.isFile(), "出荷 skill-exp.yml が見つからない: " + file.getAbsolutePath());
        ConfigurationSection table = YamlConfiguration.loadConfiguration(file)
                .getConfigurationSection("smithing.exp-per-material");
        assertNotNull(table, SKILL_EXP + " に smithing.exp-per-material 節が無い");
        Set<String> keys = new LinkedHashSet<>();
        for (String key : table.getKeys(false)) {
            keys.add(normalize(key));
        }
        return keys;
    }

    private static ConfigurationSection loadCatalogItems() {
        File file = new File(CATALOG);
        assertTrue(file.isFile(), "出荷カタログが見つからない: " + file.getAbsolutePath());
        ConfigurationSection items = YamlConfiguration.loadConfiguration(file).getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");
        return items;
    }

    /**
     * 正規形どおりに「儀式2本＋非儀式1本を {@code recipes:} に並べた」アイテム1件だけの fixture。
     * 出荷 yml は一切触らない。
     */
    private static ConfigurationSection fixtureItems() throws Exception {
        YamlConfiguration fixture = new YamlConfiguration();
        fixture.loadFromString(String.join("\n",
                "items:",
                "  fixture_blade:",
                "    material: IRON_SWORD",
                "    recipes:",
                "      - method: ritual",
                "        core-item: IRON_INGOT",
                "        pedestal-items:",
                "          - \"custom:covered_material x2\"",
                "      - method: ritual",
                "        core-item: GOLD_INGOT",
                "        pedestal-items:",
                "          - \"custom:uncovered_material x2\"",
                "      - method: workbench",
                "        type: shapeless",
                "        ingredients:",
                "          - \"custom:not_a_ritual_material\"",
                ""));
        ConfigurationSection items = fixture.getConfigurationSection("items");
        assertNotNull(items, "fixture の items: 節が読めていない");
        return items;
    }
}
