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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    @Test
    @DisplayName("儀式レシピの消費素材は全て smithing.exp-per-material に載っている(1つ欠けると定額へ落ちる)")
    void everyRitualMaterialHasAnExpRow() {
        Set<String> table = loadMaterialTableKeys();
        ConfigurationSection items = loadCatalogItems();

        // 表に無い素材 -> それを使っているカタログID
        TreeMap<String, Set<String>> missing = new TreeMap<>();
        int ritualCount = 0;

        for (String itemId : items.getKeys(false)) {
            ConfigurationSection recipe = items.getConfigurationSection(itemId + ".recipe");
            if (recipe == null || !"ritual".equalsIgnoreCase(recipe.getString("method", ""))) {
                continue;
            }
            ritualCount++;
            for (String token : materialTokens(recipe)) {
                if (!table.contains(token)) {
                    missing.computeIfAbsent(token, key -> new TreeSet<>()).add(itemId);
                }
            }
        }

        assertTrue(ritualCount >= MIN_EXPECTED_RITUAL_RECIPES,
                "出荷カタログの儀式レシピが " + ritualCount + " 件しかない。"
                        + "節ごと消えていないか確認すること(期待: " + MIN_EXPECTED_RITUAL_RECIPES + " 件以上)");

        if (missing.isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder();
        Set<String> affected = new TreeSet<>();
        missing.forEach((token, ids) -> {
            affected.addAll(ids);
            message.append("\n  ").append(token)
                    .append("  (").append(ids.size()).append("件で使用: ")
                    .append(String.join(", ", ids.stream().limit(4).toList()))
                    .append(ids.size() > 4 ? ", …" : "").append(")");
        });

        throw new AssertionError(
                "儀式で消費するのに smithing.exp-per-material に行が無い素材が "
                        + missing.size() + " 種類ある(影響する儀式レシピ " + affected.size() + " 件)。"
                        + "ArsProgressionBridge#grantSmithingCraftExp は1つでも引けないと"
                        + "素材合計を捨てて ars-smithing.exp-per-craft の定額へ戻すので、"
                        + "これらのレシピは素材価値と無関係な固定EXPになる。"
                        + "skill-exp.yml に行を足すこと(値の目安はファイル内のコメント参照)。"
                        + message);
    }

    /** core-item と pedestal-items を、個数を落とした正規化トークンで返す。 */
    private static List<String> materialTokens(ConfigurationSection recipe) {
        List<String> raw = new ArrayList<>();
        String core = recipe.getString("core-item", "");
        if (core != null && !core.isBlank()) {
            raw.add(core);
        }
        raw.addAll(recipe.getStringList("pedestal-items"));

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
}
