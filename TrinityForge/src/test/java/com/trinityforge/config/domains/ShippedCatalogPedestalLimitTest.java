package com.trinityforge.config.domains;

import com.trinityforge.stats.RecipeSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code items/catalog.yml} の儀式レシピが、台座リングの物理上限 <b>48 台</b>を
 * 超えていないことを固定する(2026-08-02、上限を 16→48 に訂正)。
 *
 * <h2>なぜ 48 なのか(誤診の訂正)</h2>
 * かつてこのテストは上限を 16 と誤って固定していた(コアからチェビシェフ距離 2 の外周だけ＝
 * 5x5 から 3x3 を引いた 16 マスしか見ていなかった)。実際のフォーク実装
 * {@code RitualManager#findNearbyPedestals} は<b>その 16 マスの外周を Y ±1 の3段ぶん</b>走査し、
 * 同じ ingredient リストへ積む(16 × 3 = 48)。{@code RitualRecipe#matches} は台座アイテムの
 * multiset 完全一致しか見ず段を区別しないため、19 台程度のレシピは Y ±1 の段を使えば
 * 普通に成立する。この誤診により、超過 4 レシピ(harvest_hoe/herb_hat/leyline_shovel/
 * bedrock_greaves)の素材を「永久にクラフト不可」と誤判定して不要に軽量化する事故があった
 * (本コミットで素材を元に戻し、上限も 48 へ訂正した)。
 *
 * <h2>なぜテストで縛るのか</h2>
 * 一方で 48 台を超えるレシピは<b>段をいくら積んでも物理的に置き場が無い</b>。
 * {@code pedestal-items} の {@code "NAME xN"} は<b>台座 N 台ぶんに展開される</b>ので、
 * 行数ではなく合計台数で数える必要がある。48 を超えたレシピは登録こそされるが、
 * フォークの {@code RitualRecipe#matches} が台座数の<b>完全一致</b>を要求するため
 * <b>永久に成立しない</b>。つまり「レシピ帳には出るのに、素材を全部揃えても絶対に作れない」
 * という無言死になる。ログにも出ず、プレイヤーが「作れない」と報告するまで誰も気づけない。
 *
 * <h2>エディタ側の検証では足りない理由</h2>
 * {@code tools/config-editor/lib/schema.js} の {@code validatePedestalItems} は保存時に弾くが、
 * <b>yml を直接書いた場合はそこを通らない</b>(スクリプト生成・手編集・エージェントによる一括追加)。
 * 書き込み側を通らない経路がある以上、出荷 yml そのものを固定するテストが要る。
 */
class ShippedCatalogPedestalLimitTest {

    private static final Logger LOG = Logger.getLogger("ShippedCatalogPedestalLimitTest");
    private static final String CATALOG = "src/main/resources/items/catalog.yml";

    /** 儀式コア周囲の台座リング(Y±1の3段合計、チェビシェフ距離2の外周×3段)の物理上限。 */
    private static final int MAX_PEDESTALS = 48;

    /** 儀式レシピの本数。節ごと消えたことに気づくための下限。 */
    private static final int MIN_EXPECTED_RITUALS = 100;

    /** {@code "NAME xN"} の N。無い行は 1 台。 */
    private static final Pattern COUNT = Pattern.compile(".*\\s+x(\\d+)$");

    private static ItemCatalogConfig.ParseResult parseShipped() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(CATALOG);
        assertTrue(java.nio.file.Files.isRegularFile(path),
                "出荷カタログが見つからない: " + path.toAbsolutePath());
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        cfg.loadFromString(java.nio.file.Files.readString(path));
        var items = cfg.getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");
        return ItemCatalogConfig.parse(items, LOG);
    }

    private static int pedestalTotal(List<String> pedestals) {
        int total = 0;
        for (String entry : pedestals) {
            if (entry == null) {
                continue;
            }
            Matcher matcher = COUNT.matcher(entry.trim());
            total += matcher.matches() ? Integer.parseInt(matcher.group(1)) : 1;
        }
        return total;
    }

    @Test
    @DisplayName("出荷カタログの儀式レシピは台座 48 台を超えない(超えると永久にクラフト不可)")
    void noShippedRitualExceedsThePedestalRing() throws Exception {
        var templates = parseShipped().templates();

        int rituals = 0;
        List<String> over = new ArrayList<>();
        for (var entry : templates.entrySet()) {
            for (RecipeSpec recipe : entry.getValue().recipes()) {
                if (recipe.method() != RecipeSpec.Method.RITUAL) {
                    continue;
                }
                rituals++;
                int total = pedestalTotal(recipe.pedestalItems());
                if (total > MAX_PEDESTALS) {
                    over.add(entry.getKey() + "(台座" + total + "台: " + recipe.pedestalItems() + ")");
                }
            }
        }

        assertTrue(rituals >= MIN_EXPECTED_RITUALS,
                "出荷カタログの儀式レシピが " + rituals + " 件しかない(期待: "
                        + MIN_EXPECTED_RITUALS + " 件以上)。節ごと消えていないか確認すること");

        assertTrue(over.isEmpty(),
                "台座リングの上限 " + MAX_PEDESTALS + " 台を超える儀式レシピがある。"
                        + "登録はされるが RitualRecipe#matches が台数の完全一致を要求するため"
                        + "【永久にクラフトできない】。該当: " + over);
    }
}
