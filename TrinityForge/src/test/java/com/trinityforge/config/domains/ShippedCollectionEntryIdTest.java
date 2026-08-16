package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>図鑑({@code progression/collection.yml})の items エントリが、実在のアイテムIDで書かれていること</b>
 * (2026-08-16)。
 *
 * <h2>何が起きていたか</h2>
 * {@code thread} カテゴリ45件が <b>ArsPaper {@code threads.yml} の生キー</b>({@code angler})で
 * 書かれていた。実際のアイテムIDは {@code catalog.yml} の {@code thread_angler} で、
 * {@code ThreadItem} が {@code "thread_" + key} を登録IDにしているためズレていた。
 *
 * <p>症状は2つ出る。<b>どちらもエラーにならず、静かに壊れる</b>:
 * <ul>
 *   <li>表示: {@code CollectionEntryNames} は解決できないIDを生IDのまま出すので、
 *       図鑑のアイテム欄に {@code angler} と並ぶ。</li>
 *   <li>記録: {@code CollectionListener} は<b>カタログID</b>({@code thread_angler})で記録するので、
 *       {@code angler} のエントリには何を拾っても一生チェックが入らない。</li>
 * </ul>
 *
 * <h2>検査の形</h2>
 * 許可リストを作らない(リスト自体が誤ると検査ごと無効化されるため)。代わりに
 * <b>「接頭辞を落として書かれていないか」を機械的に当てる</b>:
 * カタログに存在しないエントリ {@code X} について、{@code <何か>_X} という<b>カタログIDが実在する</b>なら、
 * それは接頭辞落ちである。ArsPaper 側にしか実体が無いエントリ(ダンジョンの印・ソースリンク等)は
 * カタログに接尾一致するIDを持たないので、この規則には引っかからない。
 */
class ShippedCollectionEntryIdTest {

    private static final String COLLECTION = "src/main/resources/progression/collection.yml";
    private static final String CATALOG = "src/main/resources/items/catalog.yml";

    @Test
    @DisplayName("図鑑のアイテムIDが接頭辞落ち(thread_angler を angler と書く)していない")
    void itemEntriesAreNotWrittenWithADroppedPrefix() {
        Set<String> catalogIds = catalogIds();
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (String category : itemCategories()) {
            for (String entry : entries(category)) {
                if (entry.equals(entry.toUpperCase())) {
                    continue; // 大文字＝バニラ Material の規約
                }
                checked++;
                if (catalogIds.contains(entry)) {
                    continue;
                }
                List<String> suffixMatches = catalogIds.stream()
                        .filter(id -> id.endsWith("_" + entry))
                        .sorted()
                        .toList();
                if (!suffixMatches.isEmpty()) {
                    offenders.add(category + "/" + entry + " → " + suffixMatches);
                }
            }
        }
        assertTrue(checked > 300, "検査したエントリが " + checked + " 件しかない(図鑑の構造が変わって空振りしている)");
        assertTrue(offenders.isEmpty(),
                "図鑑のエントリが接頭辞落ちのIDで書かれている: " + offenders
                        + " —— 表示が生IDになるだけでなく、記録側はカタログIDで記録するので"
                        + "そのエントリは永久に未収集のままになる");
    }

    @Test
    @DisplayName("thread カテゴリは全件がカタログの thread_* で、出荷済み(draft でない)")
    void threadCategoryPointsAtShippedCatalogEntries() {
        YamlConfiguration catalog = load(CATALOG);
        List<String> entries = entries("thread");
        assertEquals(45, entries.size(), "thread カテゴリの件数");
        List<String> problems = new ArrayList<>();
        for (String entry : entries) {
            if (!entry.startsWith("thread_")) {
                problems.add(entry + ": thread_ で始まっていない");
                continue;
            }
            ConfigurationSection item = catalog.getConfigurationSection("items." + entry);
            if (item == null) {
                problems.add(entry + ": catalog.yml に無い");
            } else if (item.getBoolean("draft")) {
                problems.add(entry + ": draft(準備中)なのでどの経路でも解決されない");
            } else if (item.getString("display-name") == null) {
                problems.add(entry + ": display-name が無い(図鑑に Material 名が出る)");
            }
        }
        assertTrue(problems.isEmpty(), "図鑑の thread カテゴリが実アイテムを指していない: " + problems);
    }

    // ---- 出荷 yml の読み出し -------------------------------------------------

    private static YamlConfiguration load(String relative) {
        File file = new File(relative);
        if (!file.isFile()) {
            throw new AssertionError("出荷configが見つからない: " + file.getAbsolutePath());
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private static Set<String> catalogIds() {
        ConfigurationSection items = load(CATALOG).getConfigurationSection("items");
        if (items == null) {
            throw new AssertionError("catalog.yml に items が無い");
        }
        return new LinkedHashSet<>(items.getKeys(false));
    }

    private static Set<String> itemCategories() {
        ConfigurationSection items = load(COLLECTION).getConfigurationSection("categories.items");
        if (items == null) {
            throw new AssertionError("collection.yml に categories.items が無い");
        }
        return new LinkedHashSet<>(items.getKeys(false));
    }

    private static List<String> entries(String category) {
        return load(COLLECTION).getStringList("categories.items." + category + ".entries");
    }
}
