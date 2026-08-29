package com.trinityforge.config.domains;

import com.trinityforge.testsupport.KnownCustomItemIds;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * インフィニティの道具4種({@code infinity_pickaxe} / {@code infinity_shovel} /
 * {@code infinity_axe_tool} / {@code infinity_hoe})が<b>作業台で実際に作れる</b>ことを固定する
 * (2026-08-25, W-239)。
 *
 * <h2>なぜこのテストが要るのか</h2>
 * <p>2026-08-25 に出荷カタログ 457 件の入手経路を総ざらいしたところ、
 * <b>レシピ・ドロップ・ガチャ・取引・報酬のどこにも現れない</b>のがこの4件だけだった
 * (準備中のものを除く)。図鑑には載るので存在は見えるのに、どうやっても手に入らない
 * ——「集めてみるまで気づけない」形の穴で、机上の grep では
 * {@code items/catalog.yml} に定義があることしか分からない。
 * ユーザー決定「クラフトで作れるようにする」に従ってレシピを付けたので、
 * <b>そのレシピが消えたら落ちる</b>ようにここで固定する。
 *
 * <h2>「レシピを書いた」だけでは作れるとは限らない</h2>
 * <p>このリポジトリでレシピが無言死する経路は主に3つある。3つとも別々に検査する。
 * <ul>
 *   <li><b>素材IDの解決失敗</b>: {@code custom:<id>} は実行時にしか解決されず、
 *       失敗しても WARNING 1 行でそのレシピが捨てられる。プレイヤーからは
 *       「レシピ帳に出るのに永久に作れない」としか見えない
 *       ({@link ShippedCustomIdReferenceDriftTest} が全 yml を横断で見ているが、
 *       この4件については素材の解決先まで名指しで固定しておく)</li>
 *   <li><b>形の破綻</b>: 記号が {@code ingredients} 側に無い / 逆に使われない記号がある /
 *       3×3 に収まらない、はどれも登録時に落ちる</li>
 *   <li><b>形の重複</b>: 同じ並び・同じ素材の shaped レシピが他にもあると、
 *       先に登録されたほうだけが成立して<b>後から登録されたレシピは永久に作れない</b>。
 *       素材パレットを同じ帯のインフィニティ武器とそろえている以上、ここが一番踏みやすい</li>
 * </ul>
 */
class ShippedInfinityToolCraftabilityTest {

    private static final String CATALOG = "src/main/resources/items/catalog.yml";

    /** 2026-08-25 の総ざらいで「入手経路ゼロ」と判明した4件。 */
    private static final List<String> INFINITY_TOOLS = List.of(
            "infinity_pickaxe", "infinity_shovel", "infinity_axe_tool", "infinity_hoe");

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("インフィニティの道具4種は作業台の shaped レシピを持つ")
    void everyInfinityToolHasAWorkbenchRecipe() {
        ConfigurationSection items = catalogItems();
        List<String> withoutRoute = new ArrayList<>();
        for (String id : INFINITY_TOOLS) {
            ConfigurationSection entry = items.getConfigurationSection(id);
            assertNotNull(entry, id + " がカタログから消えている(消したのなら図鑑側も一緒に直すこと)");
            ConfigurationSection recipe = entry.getConfigurationSection("recipe");
            if (recipe == null) {
                withoutRoute.add(id);
                continue;
            }
            assertEquals("workbench", recipe.getString("method"),
                    id + " のレシピが作業台以外になっている(ユーザー決定は『クラフトで作れるようにする』)");
            assertEquals("shaped", recipe.getString("type"), id + " のレシピが shaped ではない");
        }
        assertEquals(List.of(), withoutRoute,
                withoutRoute + " に入手経路が無い。図鑑には載るのに永久に手へ入らない状態に戻っている"
                        + "(2026-08-25 の総ざらいで見つかった穴。レシピを消すなら別の経路を先に用意すること)");
    }

    @Test
    @DisplayName("4種のレシピの形と素材が作業台に登録できる状態になっている")
    void everyInfinityToolRecipeIsRegistrable() {
        Set<String> knownCustomIds = KnownCustomItemIds.load().ids();
        assertTrue(knownCustomIds.size() >= 100,
                "custom アイテムIDの一覧を読めていない(パスが壊れている?): " + knownCustomIds.size() + " 件");

        List<String> problems = new ArrayList<>();
        for (String id : INFINITY_TOOLS) {
            ConfigurationSection recipe = recipeOf(id);
            if (recipe == null) {
                continue; // 上のテストが名指しで落とす
            }
            List<String> shape = recipe.getStringList("shape");
            if (shape.isEmpty() || shape.size() > 3) {
                problems.add(id + ": shape の行数が 1〜3 でない (" + shape.size() + " 行)");
                continue;
            }
            Set<String> symbolsInShape = new TreeSet<>();
            for (String row : shape) {
                if (row.length() > 3) {
                    problems.add(id + ": shape の行が3文字を超えている \"" + row + "\"");
                }
                for (char c : row.toCharArray()) {
                    if (c != ' ') {
                        symbolsInShape.add(String.valueOf(c));
                    }
                }
            }
            ConfigurationSection ingredients = recipe.getConfigurationSection("ingredients");
            if (ingredients == null) {
                problems.add(id + ": ingredients が無い(shaped には記号→素材の対応表が要る)");
                continue;
            }
            Set<String> declared = new TreeSet<>(ingredients.getKeys(false));
            for (String symbol : symbolsInShape) {
                if (!declared.contains(symbol)) {
                    problems.add(id + ": shape の記号 '" + symbol + "' が ingredients に無い");
                }
            }
            for (String symbol : declared) {
                if (!symbolsInShape.contains(symbol)) {
                    problems.add(id + ": ingredients の記号 '" + symbol + "' が shape のどこにも出てこない");
                }
            }
            for (String symbol : declared) {
                String token = String.valueOf(ingredients.get(symbol));
                if (token.startsWith("custom:")) {
                    String customId = token.substring("custom:".length());
                    if (!knownCustomIds.contains(customId)) {
                        problems.add(id + ": 素材 '" + token + "' がどのレジストリにも無い"
                                + "(実行時は WARNING 1行でレシピごと捨てられる)");
                    }
                } else if (token.startsWith("list:")) {
                    continue; // 互換リストは ShippedDungeonKeyReachabilityTest が実在を見ている
                } else if (Material.matchMaterial(token) == null) {
                    problems.add(id + ": 素材 '" + token + "' がバニラ素材として解決できない");
                }
            }
        }
        assertEquals(List.of(), problems,
                "インフィニティの道具のレシピが作業台へ登録できない形になっている。"
                        + "登録に失敗しても起動ログの警告1行で終わるので、実機では"
                        + "『レシピ帳に出るのに永久に作れない』としか見えない: " + problems);
    }

    @Test
    @DisplayName("4種のレシピの形が他のカタログレシピと衝突しない")
    void infinityToolRecipesDoNotShadowOrGetShadowedByOtherRecipes() {
        Map<String, List<String>> bySignature = new LinkedHashMap<>();
        ConfigurationSection items = catalogItems();
        for (String id : items.getKeys(false)) {
            String signature = shapedSignature(recipeOf(items, id));
            if (signature != null) {
                bySignature.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(id);
            }
        }
        assertTrue(bySignature.size() >= 50,
                "カタログから shaped レシピをほとんど拾えていない(読み取りが壊れている?): "
                        + bySignature.size() + " 種");

        List<String> collisions = new ArrayList<>();
        for (String id : INFINITY_TOOLS) {
            String signature = shapedSignature(recipeOf(items, id));
            if (signature == null) {
                continue;
            }
            List<String> sharing = bySignature.getOrDefault(signature, List.of());
            if (sharing.size() > 1) {
                collisions.add(sharing + " が同じ形・同じ素材 (" + signature + ")");
            }
        }
        assertEquals(List.of(), collisions,
                "同じ並び・同じ素材の shaped レシピが複数ある。作業台では先に登録されたほうだけが成立し、"
                        + "後のレシピは永久に作れない(エラーも警告も出ない): " + collisions);
    }

    // ---- helpers ----

    private static ConfigurationSection catalogItems() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(CATALOG));
        ConfigurationSection items = yaml.getConfigurationSection("items");
        assertNotNull(items, "items セクションが読めない: " + new File(CATALOG).getAbsolutePath());
        assertFalse(items.getKeys(false).isEmpty(), "カタログが空に見える");
        return items;
    }

    private static ConfigurationSection recipeOf(String id) {
        return recipeOf(catalogItems(), id);
    }

    private static ConfigurationSection recipeOf(ConfigurationSection items, String id) {
        ConfigurationSection entry = items.getConfigurationSection(id);
        return entry == null ? null : entry.getConfigurationSection("recipe");
    }

    /**
     * shaped レシピを「記号名に依存しない形＋素材の並び」へ正規化する。
     * 記号は好きに付け替えられる(=別のレシピでも {@code J} と書ける)ので、記号のままでは
     * 衝突判定にならない。空白は空文字へ、記号は解決先の素材トークンへ置き換えて比べる。
     *
     * @return shaped 以外・作業台以外・読めない場合は {@code null}
     */
    private static String shapedSignature(ConfigurationSection recipe) {
        if (recipe == null
                || !"workbench".equals(recipe.getString("method"))
                || !"shaped".equals(recipe.getString("type"))) {
            return null;
        }
        List<String> shape = recipe.getStringList("shape");
        ConfigurationSection ingredients = recipe.getConfigurationSection("ingredients");
        if (shape.isEmpty() || ingredients == null) {
            return null;
        }
        List<String> resolved = new ArrayList<>();
        for (String row : shape) {
            StringBuilder cells = new StringBuilder();
            for (char c : row.toCharArray()) {
                Object value = c == ' ' ? null : ingredients.get(String.valueOf(c));
                cells.append(value == null ? "-" : String.valueOf(value)).append('|');
            }
            resolved.add(cells.toString());
        }
        return String.join("/", resolved);
    }
}
