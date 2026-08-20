package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code items/catalog.yml} の儀式レシピが<b>実機で組める台数に収まっている</b>ことを固定する
 * (2026-08-19 / W-121)。
 *
 * <h2>この検査が無いと何が起きるか(実際に起きた)</h2>
 * ArsPaper の {@code RitualManager#findNearbyPedestals} は「コアから XZ で
 * {@code max(|x|,|z|) == 2} のリング」を走査する。1段あたり <b>16 マス</b>(5x5 の外周)しかない。
 * そして<b>台座1台に置けるのは1個</b>で({@code Pedestal} はスタック数を見ない)、
 * {@code RitualRecipe#matches} は
 * <pre>pedestalIngredients.size() != pedestalItems.size() -&gt; false</pre>
 * と<b>台数の完全一致</b>を要求する。つまり yml の {@code "custom:foo x16"} は
 * 「16スタック」ではなく<b>台座16台</b>を意味する。
 *
 * <p>2026-08-19 の W-120 で、重さを出すつもりで {@code custom:magebloom_fiber x24} のように書いた結果、
 * 38 レシピが 17〜29 台を要求する状態になった。リング1段に収まらないので上下段へ台座を積まないと
 * 成立せず、しかも<b>エラーもログも出ない</b>(素材が揃っていないレシピとして黙って不成立になるだけ)。
 * プレイヤーからは「レシピどおり置いたのに始まらない」としか見えない。
 *
 * <p>Y は ±1 まで見るので理論上は 48 台まで置けるが、それは「リングを3段積む」建築であって
 * 意図した遊びではない。<b>1段(16台)を上限として固定する。</b>重さはソース量と素材のレアリティで出す。
 *
 * <p>ArsPaper 側の {@code items.yml} / {@code functional-items.yml} は {@code .gitignore} 除外で
 * クローンにも CI にも存在しないため、ここでは TF が所有する {@code items/catalog.yml} だけを見る。
 */
class ShippedRitualPedestalCapTest {

    private static final String CATALOG = "src/main/resources/items/catalog.yml";

    /** 台座リング1段のマス数。{@code RitualManager.PEDESTAL_DISTANCE = 2} の 5x5 外周。 */
    private static final int RING_CAPACITY = 16;

    /** {@code "TOKEN xN"} の N を取る。ArsPaper の {@code parseIngredientCount} と同じ記法。 */
    private static final Pattern COUNT_SUFFIX = Pattern.compile("\\sx(\\d+)$");

    /** 儀式レシピの下限件数(セクションごと消えてテストが空回りするのを防ぐ)。 */
    private static final int MIN_EXPECTED_RITUALS = 100;

    @Test
    @DisplayName("儀式レシピの台座合計は 16 台以下 — 超えるとリング1段に収まらず無言で不成立になる")
    void everyRitualFitsInOnePedestalRing() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(Path.of(CATALOG)));
        ConfigurationSection items = cfg.getConfigurationSection("items");
        assertTrue(items != null, "items: セクションが読めない。カタログの構造が変わっている");

        List<String> problems = new ArrayList<>();
        int rituals = 0;
        for (String id : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(id);
            if (item == null) continue;
            for (ConfigurationSection recipe : recipesOf(item)) {
                if (!"ritual".equalsIgnoreCase(String.valueOf(recipe.getString("method")))) continue;
                rituals++;
                int total = 0;
                for (String entry : recipe.getStringList("pedestal-items")) {
                    total += countOf(entry);
                }
                if (total > RING_CAPACITY) {
                    problems.add(id + ": 台座 " + total + " 台 (上限 " + RING_CAPACITY + ")");
                }
            }
        }

        assertTrue(rituals >= MIN_EXPECTED_RITUALS,
                "儀式レシピが " + rituals + " 件しか見つからない。検査対象ごと消えている可能性がある");
        assertTrue(problems.isEmpty(),
                "リング1段(" + RING_CAPACITY + "台)に収まらない儀式レシピがある。"
                        + "台座1台につき1個しか置けず、台数は完全一致が要求されるので、"
                        + "これらは実機で組めない:\n  " + String.join("\n  ", problems));
    }

    /**
     * 正規形(0件=なし / 1件={@code recipe:} / 2件以上={@code recipes:})の両方を拾う。
     * 片方しか見ないと、まとめ生産レシピ側の台座超過を丸ごと見逃す。
     */
    private static List<ConfigurationSection> recipesOf(ConfigurationSection item) {
        List<ConfigurationSection> out = new ArrayList<>();
        ConfigurationSection single = item.getConfigurationSection("recipe");
        if (single != null) {
            out.add(single);
        }
        ConfigurationSection multi = item.getConfigurationSection("recipes");
        if (multi != null) {
            for (String key : multi.getKeys(false)) {
                ConfigurationSection child = multi.getConfigurationSection(key);
                if (child != null) {
                    out.add(child);
                }
            }
        } else if (item.getList("recipes") instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof java.util.Map<?, ?> map) {
                    YamlConfiguration wrapper = new YamlConfiguration();
                    ConfigurationSection child = wrapper.createSection("r");
                    for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                        child.set(String.valueOf(e.getKey()), e.getValue());
                    }
                    out.add(child);
                }
            }
        }
        return out;
    }

    private static int countOf(String entry) {
        if (entry == null) return 0;
        Matcher m = COUNT_SUFFIX.matcher(entry.trim());
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }
}
