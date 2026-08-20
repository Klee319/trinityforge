package com.trinityforge.stats;

import com.trinityforge.config.domains.CraftQualityConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「config で機構ごと殺したステは lore からも消す」(2026-08-01)。
 *
 * <p>{@code stats/craft-quality.yml} の {@code workbench.upswing-scale} と
 * {@code ritual.upswing-scale} を両方 0 にすると {@code craft_upswing_bonus} はどの経路にも
 * 寄与しなくなるが、アイテム/パークが持っている値そのものは 0 ではないため、
 * 素朴に書くと lore には「上振れ↑ +0.15」と出たままになる —— 表示と実装の食い違い。
 * {@link LoreComposer#useInertStatKeys} はこれを {@link StatDisplaySpec#hideWhenZero()} と
 * 同じ規約(効かない値は出さない)へ寄せるための seam。
 */
class LoreComposerInertStatTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String UPSWING = CraftQualityConfig.WORKBENCH_UPSWING_STAT_KEY;
    private static final String DOWNSWING = CraftQualityConfig.WORKBENCH_DOWNSWING_STAT_KEY;

    private static StatDisplaySpec spec(String key, String name, int order) {
        return new StatDisplaySpec(key, name, "", LoreValueFormat.FLAT, 2, order, true, true, "",
                StatCategoryInference.infer(key));
    }

    private static final Map<String, StatDisplaySpec> TABLE = Map.of(
            UPSWING, spec(UPSWING, "上振れ↑", 10),
            DOWNSWING, spec(DOWNSWING, "下振れ↓", 20),
            "attack_damage", spec("attack_damage", "攻撃力", 30));

    private static final Map<String, Double> STATS = Map.of(
            UPSWING, 0.15,
            DOWNSWING, 10.0,
            "attack_damage", 5.0);

    private static String render(LoreComposer composer, Map<String, Double> stats) {
        return renderRequest(composer, new LoreComposeRequest(stats, Map.of(), "", 0, null, null, "", 0));
    }

    private static String renderRequest(LoreComposer composer, LoreComposeRequest request) {
        List<Component> lore = composer.compose(request, TABLE, LoreLayout.defaults());
        StringBuilder text = new StringBuilder();
        for (Component line : lore) {
            text.append(PLAIN.serialize(line)).append('\n');
        }
        return text.toString();
    }

    private static CraftQualityConfig configFrom(String yaml) {
        CraftQualityConfig config = new CraftQualityConfig();
        config.apply(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
        return config;
    }

    @Test
    @DisplayName("未配線なら従来どおり全部出る(この seam は既定で何もしない)")
    void nothingIsHiddenWithoutWiring() {
        String lore = render(new LoreComposer(), STATS);

        assertTrue(lore.contains("上振れ↑"), lore);
        assertTrue(lore.contains("下振れ↓"), lore);
        assertTrue(lore.contains("攻撃力"), lore);
    }

    @Test
    @DisplayName("無効化キーに挙がったステは、値を持っていても lore から消える")
    void inertStatsAreDroppedFromTheLore() {
        LoreComposer composer = new LoreComposer();
        composer.useInertStatKeys(() -> Set.of(UPSWING));

        String lore = render(composer, STATS);

        assertFalse(lore.contains("上振れ↑"), "効かないステの行が残っている: " + lore);
        assertTrue(lore.contains("下振れ↓"), "無効化していないステまで消してはいけない: " + lore);
        assertTrue(lore.contains("攻撃力"), lore);
    }

    @Test
    @DisplayName("キーの綴り(ケバブ/スネーク)が違っても同じステとして消える")
    void inertKeyMatchingIsCanonical() {
        LoreComposer composer = new LoreComposer();
        composer.useInertStatKeys(() -> Set.of("workbench-upswing-bonus")); // ケバブ表記

        assertFalse(render(composer, STATS).contains("上振れ↑"));
    }

    @Test
    @DisplayName("『デフォルト表示』ON でも無効化されたステは復活しない")
    void forceShowDoesNotResurrectAnInertStat() {
        LoreComposer composer = new LoreComposer();
        composer.useInertStatKeys(() -> Set.of(UPSWING));

        String lore = renderRequest(composer, new LoreComposeRequest(
                STATS, Map.of(), "", 0, null, null, "", 0, Set.of(UPSWING), Set.of(), Map.of()));

        assertFalse(lore.contains("上振れ↑"),
                "0でも出す指定は『効くが今は0』のための機能で、『そもそも効かない』を出す理由にならない: " + lore);
    }

    @Test
    @DisplayName("乗算レイヤだけを持つ無効化ステも行ごと出ない")
    void multiplierOnlyInertStatIsAlsoDropped() {
        LoreComposer composer = new LoreComposer();
        composer.useInertStatKeys(() -> Set.of(UPSWING));

        String lore = renderRequest(composer, new LoreComposeRequest(
                Map.of("attack_damage", 5.0), Map.of(), "", 0, null, null, "", 0,
                Set.of(), Set.of(), Map.of("perk", Map.of(UPSWING, 1.5))));

        assertFalse(lore.contains("上振れ↑"), "乗算行だけで復活してはいけない: " + lore);
        assertTrue(lore.contains("攻撃力"), lore);
    }

    // ---- 実際の config を繋いだ形 ----

    @Test
    @DisplayName("出荷既定(scale=1.0)を繋いでも何も消えない")
    void shippedDefaultsHideNothing() {
        LoreComposer composer = new LoreComposer();
        CraftQualityConfig config = configFrom("mode:\n  base-quality: 0\n");
        composer.useInertStatKeys(config::inertSpreadStatKeys);

        String lore = render(composer, STATS);
        assertTrue(lore.contains("上振れ↑"), lore);
        assertTrue(lore.contains("下振れ↓"), lore);
    }

    @Test
    @DisplayName("両経路 scale=0 にした瞬間、そのステだけ lore から消える(表示と実装の一致)")
    void mutingAStatOnEveryPathRemovesItsLoreLine() {
        LoreComposer composer = new LoreComposer();
        CraftQualityConfig config = configFrom("""
                workbench:
                  upswing-scale: 0.0
                ritual:
                  upswing-scale: 0.0
                """);
        composer.useInertStatKeys(config::inertSpreadStatKeys);

        String lore = render(composer, STATS);
        assertFalse(lore.contains("上振れ↑"), "どの経路でも効かないのに表示が残っている: " + lore);
        assertTrue(lore.contains("下振れ↓"), "こちらはまだ効くので残す: " + lore);
    }

    @Test
    @DisplayName("片方の経路だけ殺すと、その経路のステだけが消えてもう一方は残る")
    void mutingOnePathHidesOnlyThatPathsStat() {
        // 2026-08-01 の経路別キー分割で、上振れ/下振れは経路ごとに別のステになった。
        // 作業台の scale を 0 にしたら「作業台の上振れ↑」だけが消え、
        // 「儀式の上振れ↑」は効いたまま残る(共通キー時代は片側 0 では何も消せなかった)。
        String workbenchUpswing = CraftQualityConfig.WORKBENCH_UPSWING_STAT_KEY;
        String ritualUpswing = CraftQualityConfig.RITUAL_UPSWING_STAT_KEY;
        Map<String, StatDisplaySpec> table = Map.of(
                workbenchUpswing, spec(workbenchUpswing, "作業台上振れ↑", 10),
                ritualUpswing, spec(ritualUpswing, "儀式上振れ↑", 20));
        Map<String, Double> stats = Map.of(workbenchUpswing, 0.15, ritualUpswing, 0.15);

        LoreComposer composer = new LoreComposer();
        CraftQualityConfig config = configFrom("workbench:\n  upswing-scale: 0.0\n");
        composer.useInertStatKeys(config::inertSpreadStatKeys);

        StringBuilder text = new StringBuilder();
        for (Component line : composer.compose(
                new LoreComposeRequest(stats, Map.of(), "", 0, null, null, "", 0),
                table, LoreLayout.defaults())) {
            text.append(PLAIN.serialize(line)).append('\n');
        }
        String lore = text.toString();

        assertFalse(lore.contains("作業台上振れ↑"), "作業台側は殺したので消えるべき: " + lore);
        assertTrue(lore.contains("儀式上振れ↑"), "儀式側は効いたままなので残るべき: " + lore);
    }
}
