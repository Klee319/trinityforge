package com.trinityforge.listeners;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解放式カスタムポーション({@code progression/crafting-features.yml} の {@code brew-unlocks})の
 * <b>素材が全て醸造EXP表に載っている</b>ことを固定する
 * (2026-08-21 ユーザー要望「カスタムポーションの素材に応じて醸造の経験値を設定して」)。
 *
 * <p><b>載っていないと何が起きるか</b>: 解放式ポーションは完成品のベースが {@code WATER} へ倒れるので
 * {@code brew_result} からは何も引けない({@link AlchemyBrewResultExpTest} の W-170 と同じ理由で、
 * こちらは PDC の印も {@code WATER} になる)。したがって素材行が無い解放式ポーションは
 * <b>種類に関係なく {@code alchemy_brew_exp} の定額</b>へ落ちる ―― ラヴェジャーの革を使う耐性IIも
 * 砂糖の俊敏IIIも同じEXP、という状態が<b>エラーもログも無しに</b>成立する。
 *
 * <p><b>許可リストにしない</b>: 母集合は出荷 {@code crafting-features.yml} の
 * {@code brew-unlocks} から自動で決まる。新しいカスタムポーションを足したら、その素材にEXPを
 * 決めるまでこのテストが落ちる ―― 「足したのに定額のままだった」を人間の注意力に頼らない。
 */
class BrewUnlockIngredientExpCoverageTest {

    private static final File CRAFTING_FEATURES =
            new File("src/main/resources/progression/crafting-features.yml");
    private static final File ALCHEMY =
            new File("src/main/resources/skills/base/alchemy_progression.yml");

    /** {@code brew-unlocks} に現れる素材トークンを重複なしで(出現順に)集める。 */
    private static Set<String> brewUnlockIngredients() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(CRAFTING_FEATURES);
        ConfigurationSection unlocks = yaml.getConfigurationSection("brew-unlocks");
        assertTrue(unlocks != null && !unlocks.getKeys(false).isEmpty(),
                "出荷 crafting-features.yml に brew-unlocks が無い。"
                        + "母集合が消えるとこのテストは何も検査しなくなるので、"
                        + "本当に廃止したのかを先に確かめること");
        Set<String> ingredients = new LinkedHashSet<>();
        for (String group : unlocks.getKeys(false)) {
            for (Map<?, ?> potion : unlocks.getMapList(group + ".potions")) {
                Object ingredient = potion.get("ingredient");
                if (ingredient != null && !String.valueOf(ingredient).isBlank()) {
                    ingredients.add(String.valueOf(ingredient).trim());
                }
            }
        }
        return ingredients;
    }

    private static ConfigurationSection brewIngredientTable() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(ALCHEMY);
        ConfigurationSection table = yaml.getConfigurationSection("experience.brew_ingredient");
        assertTrue(table != null, "alchemy_progression.yml の experience.brew_ingredient が読めない");
        return table;
    }

    @Test
    @DisplayName("解放式カスタムポーションの素材は全て brew_ingredient に正の値を持つ")
    void everyUnlockableBrewIngredientHasItsOwnExpValue() {
        Set<String> ingredients = brewUnlockIngredients();
        ConfigurationSection table = brewIngredientTable();

        List<String> missing = new ArrayList<>();
        for (String ingredient : ingredients) {
            if (table.getDouble(ingredient, 0.0) <= 0.0) {
                missing.add(ingredient);
            }
        }
        assertTrue(missing.isEmpty(),
                "この素材に醸造EXPが決まっていない＝そのカスタムポーションは種類に関係なく"
                        + " alchemy_brew_exp の定額へ落ちる(無言): " + missing);
    }

    @Test
    @DisplayName("バニラ醸造と素材を共有する行は、その素材で作れるバニラポーションと同額に留める")
    void ingredientsSharedWithVanillaBrewsKeepTheirVanillaValue() {
        ConfigurationSection ingredientTable = brewIngredientTable();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(ALCHEMY);
        ConfigurationSection resultTable = yaml.getConfigurationSection("experience.brew_result");
        assertTrue(resultTable != null, "experience.brew_result が読めない");

        // 「素材 → その素材でバニラが作るポーション」。素材行は brew_result より優先されるので、
        // ここがズレるとカスタムポーションを触ったつもりでバニラ醸造のEXPまで動く。
        Map<String, String> shared = Map.of(
                "SUGAR", "SWIFTNESS",
                "RABBIT_FOOT", "LEAPING",
                "GLISTERING_MELON_SLICE", "HEALING",
                "GOLDEN_CARROT", "NIGHT_VISION");

        for (Map.Entry<String, String> pair : shared.entrySet()) {
            double vanilla = resultTable.getDouble(pair.getValue(), -1.0);
            assertTrue(vanilla > 0.0,
                    pair.getValue() + " が brew_result から消えている(共有関係の前提が崩れている)");
            assertEquals(vanilla, ingredientTable.getDouble(pair.getKey(), -1.0), 1e-9,
                    pair.getKey() + " の素材EXPは brew_result." + pair.getValue()
                            + " と同額でなければならない。素材行は結果表より優先されるので、"
                            + "ここを動かすと同じ素材を使う【バニラの醸造のEXPも一緒に動く】");
        }
    }

    @Test
    @DisplayName("カスタム素材(custom:)はバニラ醸造と共有しないので自由に決めてよい")
    void customIngredientsAreNotSharedWithAnyVanillaBrew() {
        Set<String> ingredients = brewUnlockIngredients();
        List<String> custom = ingredients.stream()
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith("custom:"))
                .toList();

        assertFalse(custom.isEmpty(), "custom: 素材が1件も読めていない(パースの想定違い)");
        ConfigurationSection table = brewIngredientTable();
        for (String id : custom) {
            assertTrue(table.getDouble(id, 0.0) > 0.0,
                    id + " に醸造EXPが無い。custom: 素材はバニラと共有しないので、"
                            + "定額へ落とす理由が無い");
        }
    }
}
