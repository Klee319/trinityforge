package com.trinityforge.config.domains;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code progression/collection.yml} のうち<b>バニラ Material として書かれた図鑑エントリ</b>が
 * 実在する item Material であることを固定する (2026-07-31, K-11 の再発防止)。
 *
 * <p><b>なぜ必要か</b>: 図鑑エントリのタイプミスは<b>警告もエラーも出ない</b>。
 * {@code CollectionListener#watchedConfigIds} は {@code Material.matchMaterial} が解決できない
 * トークンを「ArsPaper のカスタムID」として扱うため、綴りを間違えた Material 名は
 * 「どのアイテムにも一致しない監視ID」として静かに生き残り、<b>永久に埋まらない枠</b>になる。
 * これは K-11(素のバニラ品が1件も記録されず {@code items.structure} 16件が錠前だった)と
 * 同じ「集めてみるまで気づけない」形の不具合で、しかも分母
 * ({@code collection.scope: all} = 140件)に入るので {@code goal_completionist}(percent: 100)を
 * 到達不能にする。
 *
 * <p>大文字トークンだけを検査するのは、小文字IDが ArsPaper 側 {@code materials.yml} /
 * {@code sourcejars.yml} 由来のカスタムIDで、そのソースは {@code .gitignore} 除外のため
 * ワークツリーに存在しないことがあり照合できないため。mob カテゴリは EntityType なので対象外。
 */
class ShippedCollectionVanillaEntriesTest {

    private static final String COLLECTION = "src/main/resources/progression/collection.yml";

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("items カテゴリの大文字エントリは全部が実在するバニラ item Material")
    void uppercaseItemEntriesAreRealItemMaterials() {
        List<String> tokens = uppercaseItemEntries();
        assertFalse(tokens.isEmpty(),
                "collection.yml の items カテゴリからバニラ Material を1件も読めていない"
                        + "(パス構造が変わったならこのテストも直すこと)");
        for (String token : tokens) {
            Material material = Material.matchMaterial(token);
            assertNotNull(material, token + " は Material として解決できない"
                    + "(カスタムID扱いになり、どのアイテムにも一致しない永久空き枠が生える)");
            assertTrue(material.isItem(), token + " はアイテムではない(拾えないので記録経路が無い)");
        }
    }

    @Test
    @DisplayName("items.structure は空にならない(K-11 で16件が丸ごと到達不能だった枠)")
    void structureCategoryStillCarriesVanillaLoot() {
        ConfigurationSection structure = itemCategories().getConfigurationSection("structure");
        assertNotNull(structure, "items.structure カテゴリが消えている");
        List<String> entries = structure.getStringList("entries");
        assertFalse(entries.isEmpty(), "items.structure の entries が空");
        for (String raw : entries) {
            Material material = Material.matchMaterial(raw.trim());
            assertNotNull(material, raw + " は Material として解決できない");
            assertTrue(material.isItem(), raw + " はアイテムではない");
        }
    }

    private static ConfigurationSection itemCategories() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(COLLECTION));
        ConfigurationSection items = yaml.getConfigurationSection("categories.items");
        assertNotNull(items, "categories.items セクションが読めない: " + new File(COLLECTION).getAbsolutePath());
        return items;
    }

    /** items カテゴリの entries のうち、Material 名の書き方(大文字/数字/下線のみ)をしているもの。 */
    private static List<String> uppercaseItemEntries() {
        ConfigurationSection items = itemCategories();
        List<String> out = new ArrayList<>();
        for (String categoryId : items.getKeys(false)) {
            ConfigurationSection category = items.getConfigurationSection(categoryId);
            if (category == null) {
                continue;
            }
            for (String raw : category.getStringList("entries")) {
                String token = raw == null ? "" : raw.trim();
                if (!token.isEmpty() && token.matches("[A-Z0-9_]+")) {
                    out.add(token);
                }
            }
        }
        return out;
    }
}
