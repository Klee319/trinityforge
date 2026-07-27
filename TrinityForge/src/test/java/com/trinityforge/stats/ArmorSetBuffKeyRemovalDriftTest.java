package com.trinityforge.stats;

import com.trinityforge.command.StatsCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ドリフト検知(SKILL_TREE armor-set-buffs 全面移行 §3): 旧4キー
 * ({@code light-armor-set-bonus-multiplier} / {@code heavy-armor-set-bonus-multiplier} /
 * {@code light-armor-set-dodge-chance} / {@code heavy-armor-set-knockback-resistance}) は
 * {@code armor-set-bonus} 1本 + skilltree {@code set-buffs} スキーマへ統一済みで、廃止された。
 * {@link StatVocabulary} / {@code combat/base-stats.yml} / {@code stats/lore.yml} /
 * {@link StatsCategory} のいずれにも復活していないことを機械的に確認する
 * (config-editor 側の同種チェックは {@code test/armor-set-bonus-migration.test.js} が担う)。
 */
class ArmorSetBuffKeyRemovalDriftTest {

    private static final List<String> REMOVED_KEYS = List.of(
            "light_armor_set_bonus_multiplier", "heavy_armor_set_bonus_multiplier",
            "light_armor_set_dodge_chance", "heavy_armor_set_knockback_resistance");

    @Test
    void removedKeysAreNotRegisteredInStatVocabulary() {
        Set<String> known = StatVocabulary.allKeys();
        for (String key : REMOVED_KEYS) {
            assertFalse(known.contains(key), key + " が StatVocabulary に復活している");
        }
        assertTrue(known.contains("armor_set_bonus"), "armor_set_bonus が StatVocabulary に登録されていない");
    }

    @Test
    void removedKeysAreNotClassifiedByStatsCategory() {
        // OTHER/ALL は「他のどのカテゴリにも属さないキー」の受け皿なので、未登録キーに対して
        // includes()==true を返すのが正しい仕様(StatsCategoryCoverageTestと同じ除外)。
        for (StatsCategory category : java.util.EnumSet.complementOf(
                java.util.EnumSet.of(StatsCategory.ALL, StatsCategory.OTHER))) {
            for (String key : REMOVED_KEYS) {
                assertFalse(category.includes(key), key + " が StatsCategory." + category + " に復活している");
            }
        }
        assertTrue(StatsCategory.ARMOR.includes("armor_set_bonus"),
                "armor_set_bonus が StatsCategory.ARMOR に分類されていない");
    }

    @Test
    void removedKeysAreNotInShippedBaseStats() throws Exception {
        try (InputStream in = ArmorSetBuffKeyRemovalDriftTest.class.getClassLoader()
                .getResourceAsStream("combat/base-stats.yml")) {
            assertNotNull(in, "出荷リソース combat/base-stats.yml が見つからない");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection stats = yaml.getConfigurationSection("base-stats");
            assertNotNull(stats, "combat/base-stats.yml に base-stats: セクションが無い");
            for (String key : REMOVED_KEYS) {
                assertFalse(stats.contains(key.replace('_', '-')),
                        key + " が combat/base-stats.yml に復活している");
            }
            assertTrue(stats.contains("armor-set-bonus"), "armor-set-bonus が base-stats.yml に無い");
        }
    }

    @Test
    void removedKeysAreNotInShippedLore() throws Exception {
        try (InputStream in = ArmorSetBuffKeyRemovalDriftTest.class.getClassLoader()
                .getResourceAsStream("stats/lore.yml")) {
            assertNotNull(in, "出荷リソース stats/lore.yml が見つからない");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection stats = yaml.getConfigurationSection("stats");
            assertNotNull(stats, "stats/lore.yml に stats: セクションが無い");
            for (String key : REMOVED_KEYS) {
                assertFalse(stats.contains(key.replace('_', '-')),
                        key + " が stats/lore.yml に復活している");
            }
            assertTrue(stats.contains("armor-set-bonus"), "armor-set-bonus が lore.yml に無い");
        }
    }
}
