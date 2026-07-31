package com.trinityforge.stats;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code stats/lore.yml} の {@code order} がカテゴリ内で一意であることを機械的に固定する
 * (2026-07-31 レビュー指摘3)。
 *
 * <p>なぜ必要か: {@code LoreComposer} の比較子は
 * {@code comparingInt(order).thenComparing(statKey)} なので、order が重複すると
 * <b>タイブレークがキー名の辞書順になり、画面の並びが yml の記述順から静かに外れる</b>。
 * 行が落ちるわけではないので機能テストは全部緑のまま、目視でしか気づけない
 * (実際 workbench-/ritual- の分割で 201 が2件・202 が2件でき、儀式側が作業台側より上に出ていた)。
 *
 * <p>order は<b>カテゴリ内</b>のソートキーなので、カテゴリを跨いだ同値は正常
 * ({@link StatDisplaySpec} の javadoc 参照)。したがって検査もカテゴリ単位で行う。
 */
class LoreOrderUniquenessTest {

    /** {@code order} 未宣言時の既定 (LoreConfig と揃えること)。 */
    private static final int DEFAULT_ORDER = 100;

    @Test
    @DisplayName("lore.yml の order は同一カテゴリ内で重複しない")
    void ordersAreUniqueWithinEachCategory() throws Exception {
        ConfigurationSection stats = loreStats();
        // カテゴリ -> order -> そのorderを持つキー
        Map<StatCategory, Map<Integer, List<String>>> byCategory = new LinkedHashMap<>();
        int scanned = 0;
        for (String key : stats.getKeys(false)) {
            ConfigurationSection entry = stats.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            scanned++;
            StatCategory category = entry.contains("category")
                    ? StatCategory.parse(entry.getString("category"))
                    : StatCategoryInference.infer(key);
            byCategory.computeIfAbsent(category, c -> new LinkedHashMap<>())
                    .computeIfAbsent(entry.getInt("order", DEFAULT_ORDER), o -> new ArrayList<>())
                    .add(key);
        }
        // 空振り防止 (パス変更やパース失敗で 0 件走査になっていないこと)。
        assertTrue(scanned > 50, "lore.yml の stat を走査できていない(走査数=" + scanned + ")");

        List<String> duplicates = new ArrayList<>();
        byCategory.forEach((category, orders) -> orders.forEach((order, keys) -> {
            if (keys.size() > 1) {
                duplicates.add(category + " order=" + order + " -> " + String.join(", ", keys));
            }
        }));
        assertEquals(List.of(), duplicates,
                "同一カテゴリ内で order が重複している(表示順がキー名の辞書順に化けるので空き番へ振り直すこと)");
    }

    private static ConfigurationSection loreStats() throws Exception {
        try (InputStream in = LoreOrderUniquenessTest.class.getClassLoader()
                .getResourceAsStream("stats/lore.yml")) {
            assertNotNull(in, "出荷リソース stats/lore.yml が見つからない");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection stats = yaml.getConfigurationSection("stats");
            assertNotNull(stats, "stats/lore.yml に stats: セクションが無い");
            return stats;
        }
    }
}
