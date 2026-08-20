package com.trinityforge.stats.status;

import com.trinityforge.command.StatsCategory;
import com.trinityforge.stats.StatDisplaySpec;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatValueRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code /tf status} GUI の内容を作る純関数群 (2026-07-29)。
 *
 * <p>Bukkit に依存しないのでヘッドレスに単体テストできる。数値の整形は
 * {@link StatValueRenderer} 一本に通すので、{@code /tf stats} のチャット出力と必ず同じ値になる。
 */
public final class StatusGuiModel {

    /** 表示するカテゴリと並び順。{@link StatsCategory#ALL} は「全部入り」なので節にはしない。 */
    private static final List<StatsCategory> ORDER = List.of(
            StatsCategory.ATTACK, StatsCategory.ARMOR, StatsCategory.CRAFT,
            StatsCategory.GATHERING, StatsCategory.UTILITY, StatsCategory.ARS, StatsCategory.OTHER);

    private StatusGuiModel() {
    }

    /** 1つのステータス行。 */
    public record Row(String key, String label, String value) {
        public Row {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
        }
    }

    /** 1カテゴリぶんの並び。 */
    public record Section(StatsCategory category, String label, List<Row> rows) {
        public Section {
            rows = List.copyOf(rows);
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    public static String labelOf(StatsCategory category) {
        return switch (category) {
            case ATTACK -> "攻撃";
            case ARMOR -> "防御";
            case CRAFT -> "生産";
            case GATHERING -> "採取";
            case UTILITY -> "補助";
            case ARS -> "魔法 (Ars)";
            case OTHER -> "その他";
            case ALL -> "すべて";
        };
    }

    /**
     * 合算済みステータスをカテゴリごとに並べる。
     *
     * <p>値0のキーは落とす(装備を外しても行だけ残って「0が延々並ぶ」画面になるのを避ける)。
     * 並びは {@code stats/lore.yml} の {@code order}、未宣言キーは末尾へ。
     *
     * @param stats 合算後(乗算レイヤ適用後)のステータス
     * @param table {@code stats/lore.yml} の表示定義
     */
    public static List<Section> sections(Map<String, Double> stats, Map<String, StatDisplaySpec> table) {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(table, "table");
        Map<StatsCategory, List<Row>> buckets = new LinkedHashMap<>();
        for (StatsCategory category : ORDER) {
            buckets.put(category, new ArrayList<>());
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(stats.entrySet());
        sorted.sort(Comparator
                .comparingInt((Map.Entry<String, Double> e) -> orderOf(e.getKey(), table))
                .thenComparing(e -> StatKeys.canonical(e.getKey())));

        for (Map.Entry<String, Double> entry : sorted) {
            String key = StatKeys.canonical(entry.getKey());
            double value = entry.getValue() == null ? 0.0 : entry.getValue();
            if (value == 0.0) {
                continue;
            }
            StatDisplaySpec spec = lookup(table, key);
            Row row = new Row(key,
                    spec != null ? spec.displayName() : key,
                    spec != null ? StatValueRenderer.render(spec, value) : StatValueRenderer.plain(value));
            // カテゴリは排他ではない実装(includes)なので、最初に一致した1つだけに載せる。
            // 両方に載せると同じステが2か所に出て「合計が合わない」と誤解される。
            boolean placed = false;
            for (StatsCategory category : ORDER) {
                if (category.includes(key)) {
                    buckets.get(category).add(row);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                buckets.get(StatsCategory.OTHER).add(row);
            }
        }
        List<Section> result = new ArrayList<>();
        for (StatsCategory category : ORDER) {
            result.add(new Section(category, labelOf(category), buckets.get(category)));
        }
        return List.copyOf(result);
    }

    /** lore 宣言が無いキーは末尾へ回す。 */
    private static int orderOf(String key, Map<String, StatDisplaySpec> table) {
        StatDisplaySpec spec = lookup(table, StatKeys.canonical(key));
        return spec != null ? spec.order() : 10_000;
    }

    private static StatDisplaySpec lookup(Map<String, StatDisplaySpec> table, String canonicalKey) {
        StatDisplaySpec direct = table.get(canonicalKey);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, StatDisplaySpec> e : table.entrySet()) {
            if (StatKeys.canonical(e.getKey()).equals(canonicalKey)) {
                return e.getValue();
            }
        }
        return null;
    }
}
