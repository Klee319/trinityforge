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
            if (StatVocabulary.BASE_STATS_ONLY_KEYS.contains(key)) {
                continue;
            }
            if (!lore.contains(key)) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing,
                "StatVocabulary にあるのに stats/lore.yml に表示定義が無いキー"
                        + "(効くがアイテムに表示されない)");
    }

    /**
     * 除外リスト自体のドリフト検知(2026-08-13)。除外は「検査ごと無効化する」方向に壊れるので、
     * リストの各キーについて<b>両方向</b>を固定する:
     * <ul>
     *   <li>語彙に実在すること — 綴り間違い/廃止キーが残っていると、そのキーの除外は空振りし、
     *       本来検査したい別のキーを守っているつもりで何も守っていない状態になる</li>
     *   <li>出荷 lore.yml に<b>無い</b>こと — 除外したまま lore.yml へ戻すと、上の検査は素通りし
     *       「アイテムのロアに出ない前提のキーが実は出ている」食い違いを誰も見つけられない
     *       (2026-08-13 に実際にこの形で3キーが紛れ込んでいた)</li>
     * </ul>
     */
    @Test
    void baseStatsOnlyKeysAreRealAndAbsentFromShippedLore() throws Exception {
        Set<String> lore = canonicalLoreKeys(shippedLoreStats());
        assertTrue(!StatVocabulary.BASE_STATS_ONLY_KEYS.isEmpty(), "除外リストが空(この検査が空振り)");
        List<String> notInVocabulary = new ArrayList<>();
        List<String> stillInLore = new ArrayList<>();
        for (String key : StatVocabulary.BASE_STATS_ONLY_KEYS) {
            if (!StatVocabulary.isKnown(key)) {
                notInVocabulary.add(key);
            }
            if (lore.contains(key)) {
                stillInLore.add(key);
            }
        }
        assertEquals(List.of(), notInVocabulary,
                "BASE_STATS_ONLY_KEYS に語彙へ無いキーがある(除外が空振りしている)");
        assertEquals(List.of(), stillInLore,
                "BASE_STATS_ONLY_KEYS のキーが stats/lore.yml にも定義されている"
                        + "(base-stats.yml 専用の定数なのでロア表示設定に置かない。"
                        + "ロアに出したいなら除外リストから外すこと)");
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
