package com.trinityforge.stats;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ドリフト検知: {@link StatVocabulary} に登録したステキーは、出荷 {@code stats/lore.yml} にも
 * 表示定義が要る。{@code LoreComposer} は lore.yml の定義だけを走査するため、片方だけ足すと
 * <b>ステは効いているのにアイテムに一行も出ない</b>という状態になる。
 *
 * <p>効いてはいるので実プレイでも既存テストでも落ちず、「このパーク意味あるのか？」という形でしか
 * 表面化しない。実際 2026-07-26 の監査で未登録キーが30件積み上がっているのが見つかった(同日中に投入済み)。
 * 以後は増えた瞬間にここで落ちる。
 *
 * <p>逆方向(lore.yml にあるが語彙に無い)は検査しない — {@code durability} や {@code item_cooldown} の
 * ように「アイテム固有で、パークからは供給しない」表示専用ステが正当に存在するため。
 */
class LoreVocabularyCoverageTest {

    private static ConfigurationSection shippedLoreStats() throws Exception {
        try (InputStream in = LoreVocabularyCoverageTest.class.getClassLoader()
                .getResourceAsStream("stats/lore.yml")) {
            assertNotNull(in, "出荷リソース stats/lore.yml が見つからない");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection stats = yaml.getConfigurationSection("stats");
            assertNotNull(stats, "stats/lore.yml に stats: セクションが無い");
            return stats;
        }
    }

    private static Set<String> canonicalLoreKeys(ConfigurationSection stats) {
        Set<String> keys = new LinkedHashSet<>();
        for (String raw : stats.getKeys(false)) {
            keys.add(StatKeys.canonical(raw));
        }
        return keys;
    }

    @Test
    void everyVocabularyKeyHasALoreDisplayEntry() throws Exception {
        Set<String> lore = canonicalLoreKeys(shippedLoreStats());
        // 空振り防止のアンカー。両側が空でも上の assertEquals は通ってしまうため。
        assertTrue(lore.contains("attack_power") && StatVocabulary.isKnown("attack_power"),
                "基準キー attack_power が両側に無い(比較が空振りしている疑い)");
        List<String> missing = new ArrayList<>();
        for (String key : StatVocabulary.allKeys()) {
            if (!lore.contains(key)) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing,
                "StatVocabulary にあるのに stats/lore.yml に表示定義が無いキー"
                        + "(効くがアイテムに表示されない)");
    }

    @Test
    void everyLoreEntryDeclaresANameAndAResolvableCategory() throws Exception {
        ConfigurationSection stats = shippedLoreStats();
        List<String> nameless = new ArrayList<>();
        List<String> miscategorised = new ArrayList<>();
        for (String raw : stats.getKeys(false)) {
            ConfigurationSection entry = stats.getConfigurationSection(raw);
            if (entry == null || entry.getString("name", "").isBlank()) {
                nameless.add(raw);
                continue;
            }
            String category = entry.getString("category");
            // category 未記載は OTHER 扱いで正当。書いてあるのに OTHER へ落ちるのは綴り間違いで、
            // 「なぜかその他欄に出る」という形でしか気付けないので機械的に弾く。
            if (category != null && !category.isBlank()
                    && StatCategory.parse(category) == StatCategory.OTHER
                    && !"other".equalsIgnoreCase(category.trim())) {
                miscategorised.add(raw + " -> " + category);
            }
        }
        assertEquals(List.of(), nameless, "stats/lore.yml で name が空のエントリ");
        assertEquals(List.of(), miscategorised,
                "category の綴りが StatCategory.parse で解決できず OTHER に落ちるエントリ");
    }
}
