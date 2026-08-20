package com.trinityforge.stats.status;

import com.trinityforge.command.StatsCategory;
import com.trinityforge.stats.LoreValueFormat;
import com.trinityforge.stats.StatCategory;
import com.trinityforge.stats.StatDisplaySpec;
import com.trinityforge.stats.StatValueRenderer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /tf status} GUI の中身 (2026-07-29)。Bukkit を使わないヘッドレス検証。
 *
 * <p>ここで守りたいのは「GUIとチャット (/tf stats) で違う数字が出ない」こと。整形は
 * {@link StatValueRenderer} 一本、合算は {@code PlayerCombatAggregate#combined()} 一本に
 * 集約してあるので、この階層では「並べ方」だけを検証する。
 */
class StatusGuiModelTest {

    private static StatDisplaySpec spec(String key, String name, LoreValueFormat format,
                                        int decimals, int order, StatCategory category) {
        return new StatDisplaySpec(key, name, "", format, decimals, order, false, true, "", category, null, null);
    }

    private static Map<String, StatDisplaySpec> table() {
        Map<String, StatDisplaySpec> table = new LinkedHashMap<>();
        table.put("attack_power", spec("attack_power", "攻撃力", LoreValueFormat.FLAT, 1, 10, StatCategory.ATTACK));
        table.put("crit_chance", spec("crit_chance", "会心率", LoreValueFormat.PERCENT, 1, 20, StatCategory.ATTACK));
        table.put("max_health", spec("max_health", "最大体力", LoreValueFormat.FLAT, 0, 5, StatCategory.DEFENSE));
        return table;
    }

    private static StatusGuiModel.Section sectionOf(List<StatusGuiModel.Section> sections, StatsCategory category) {
        return sections.stream().filter(s -> s.category() == category).findFirst().orElseThrow();
    }

    @Test
    void groupsStatsIntoTheSameCategoriesAsTheChatCommand() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("attack_power", 12.5);
        stats.put("max_health", 4.0);

        List<StatusGuiModel.Section> sections = StatusGuiModel.sections(stats, table());

        assertEquals(List.of("攻撃", "防御", "生産", "採取", "補助", "魔法 (Ars)", "その他"),
                sections.stream().map(StatusGuiModel.Section::label).toList());
        assertEquals(List.of("攻撃力"),
                sectionOf(sections, StatsCategory.ATTACK).rows().stream().map(StatusGuiModel.Row::label).toList());
        assertEquals(List.of("最大体力"),
                sectionOf(sections, StatsCategory.ARMOR).rows().stream().map(StatusGuiModel.Row::label).toList());
    }

    @Test
    void dropsZeroValuedStatsSoTheScreenIsNotFullOfZeroes() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("attack_power", 0.0);
        stats.put("crit_chance", 0.15);

        List<StatusGuiModel.Section> sections = StatusGuiModel.sections(stats, table());

        List<String> attack = sectionOf(sections, StatsCategory.ATTACK).rows().stream()
                .map(StatusGuiModel.Row::label).toList();
        assertEquals(List.of("会心率"), attack);
    }

    @Test
    void rendersValuesExactlyLikeTheChatCommandDoes() {
        Map<String, Double> stats = Map.of("crit_chance", 0.1567);

        StatusGuiModel.Row row = sectionOf(StatusGuiModel.sections(stats, table()), StatsCategory.ATTACK)
                .rows().getFirst();

        assertEquals(StatValueRenderer.render(table().get("crit_chance"), 0.1567), row.value());
        assertEquals("15.6%", row.value(), "小数第2位以下は切り捨て(四捨五入なら15.7%になる)");
    }

    @Test
    void ordersByLoreOrderAndPutsUndeclaredKeysLast() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("crit_chance", 0.1);      // order 20
        stats.put("mystery_stat", 3.0);     // lore 宣言なし → 末尾
        stats.put("attack_power", 5.0);     // order 10

        List<StatusGuiModel.Row> attack = sectionOf(StatusGuiModel.sections(stats, table()), StatsCategory.ATTACK)
                .rows();

        assertEquals(List.of("attack_power", "crit_chance"),
                attack.stream().map(StatusGuiModel.Row::key).toList());
        List<StatusGuiModel.Row> other = sectionOf(StatusGuiModel.sections(stats, table()), StatsCategory.OTHER)
                .rows();
        assertEquals(List.of("mystery_stat"), other.stream().map(StatusGuiModel.Row::key).toList());
    }

    @Test
    void undeclaredKeysFallBackToTheRawKeyAndPlainNumber() {
        StatusGuiModel.Row row = sectionOf(
                StatusGuiModel.sections(Map.of("mystery_stat", 3.456), table()), StatsCategory.OTHER)
                .rows().getFirst();

        assertEquals("mystery_stat", row.label(), "表示名が無いキーはIDをそのまま出す(捏造しない)");
        assertEquals(StatValueRenderer.plain(3.456), row.value());
    }

    @Test
    void aStatNeverAppearsInTwoCategoriesAtOnce() {
        // 同じキーが2か所に出ると「合計が合わない」と誤解される。最初に一致した1カテゴリだけに載せる。
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("attack_power", 1.0);
        stats.put("max_health", 1.0);
        stats.put("crit_chance", 1.0);

        List<StatusGuiModel.Section> sections = StatusGuiModel.sections(stats, table());

        long total = sections.stream().mapToLong(s -> s.rows().size()).sum();
        assertEquals(3, total);
        long distinct = sections.stream().flatMap(s -> s.rows().stream())
                .map(StatusGuiModel.Row::key).distinct().count();
        assertEquals(3, distinct);
    }

    @Test
    void emptyStatsStillYieldEverySectionSoTheGuiLayoutIsStable() {
        List<StatusGuiModel.Section> sections = StatusGuiModel.sections(Map.of(), table());

        assertEquals(7, sections.size(), "カテゴリ枠の数が入力で変わるとGUIのスロット配置が崩れる");
        assertTrue(sections.stream().allMatch(StatusGuiModel.Section::isEmpty));
    }

    @Test
    void sectionRowsAreImmutable() {
        StatusGuiModel.Section section = sectionOf(
                StatusGuiModel.sections(Map.of("attack_power", 1.0), table()), StatsCategory.ATTACK);
        assertNotNull(section.rows());
        assertThrowsUnsupported(() -> section.rows().add(new StatusGuiModel.Row("x", "x", "x")));
    }

    private static void assertThrowsUnsupported(Runnable runnable) {
        try {
            runnable.run();
            assertFalse(true, "変更できてしまった(List.copyOf されていない)");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    void nullValuesAreTreatedAsZeroInsteadOfThrowing() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("attack_power", null);
        stats.put("crit_chance", 0.5);

        List<StatusGuiModel.Section> sections = StatusGuiModel.sections(stats, table());

        assertEquals(List.of("crit_chance"),
                sectionOf(sections, StatsCategory.ATTACK).rows().stream().map(StatusGuiModel.Row::key).toList());
    }
}
