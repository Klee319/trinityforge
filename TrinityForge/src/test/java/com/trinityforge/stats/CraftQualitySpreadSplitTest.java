package com.trinityforge.stats;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.progression.SkillLevelSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-08-01 分離: 品質抽選の「ばらつき」補正(上振れ増加 {@code craft_upswing_bonus} /
 * 下振れ抑制 {@code craft_downswing_reduction})を、作業台クラフトと儀式(ArsPaperのリチュアル)で
 * 別々に設定できるようにした件の回帰テスト。
 *
 * <p>最重要の契約は<b>「分離しただけではバランスが1ミリも動かない」</b>こと。出荷 yml の既定値が
 * {@link CraftQualityConfig.SpreadTuning#IDENTITY} と一致することを機械的に固定する
 * (ここが崩れると、誰も設定を触っていないのにクラフト品質の分布が変わる)。
 */
class CraftQualitySpreadSplitTest {

    private static PlayerStatAggregator aggregatorReturning(Player player, Map<String, Double> stats) {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(
                new PlayerCombatAggregate(stats, Map.of(), Map.of(), Map.of(), Map.of()));
        return aggregator;
    }

    private static CraftQualityConfig configFrom(String yaml) {
        CraftQualityConfig config = new CraftQualityConfig();
        config.apply(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
        return config;
    }

    // ---- SpreadTuning の式そのもの ----

    @Test
    @DisplayName("既定値(scale=1.0 / flat=0.0)は分離前の式と完全に一致する")
    void identityTuningReproducesThePreSplitFormula() {
        CraftQualityConfig.SpreadTuning identity = CraftQualityConfig.SpreadTuning.IDENTITY;
        // 分離前: spreadUp = base + max(0, bonus) / spreadDown = max(0, base - max(0, reduction))
        assertEquals(1.75 + 0.5, identity.effectiveSpreadUp(1.75, 0.5), 1e-9);
        assertEquals(1.75, identity.effectiveSpreadDown(1.75, 0.0), 1e-9);
        assertEquals(1.75 - 0.5, identity.effectiveSpreadDown(1.75, 0.5), 1e-9);
        // 負のステは分離前と同じく0扱い(上振れが縮まったり下振れが広がったりしない)。
        assertEquals(1.75, identity.effectiveSpreadUp(1.75, -3.0), 1e-9);
        assertEquals(1.75, identity.effectiveSpreadDown(1.75, -3.0), 1e-9);
        // σは負にならない(0=その側はmode固定)。
        assertEquals(0.0, identity.effectiveSpreadDown(1.75, 999.0), 1e-9);
    }

    @Test
    @DisplayName("scale=0 はその経路にステを効かせない / flat は経路だけに乗る")
    void scaleAndFlatAreAppliedPerPath() {
        CraftQualityConfig.SpreadTuning muted = new CraftQualityConfig.SpreadTuning(0.0, 0.0, 0.0, 0.0);
        assertEquals(1.75, muted.effectiveSpreadUp(1.75, 5.0), 1e-9);
        assertEquals(1.75, muted.effectiveSpreadDown(1.75, 5.0), 1e-9);

        CraftQualityConfig.SpreadTuning flat = new CraftQualityConfig.SpreadTuning(1.0, 0.25, 1.0, 0.25);
        assertEquals(1.75 + 0.25 + 0.5, flat.effectiveSpreadUp(1.75, 0.5), 1e-9);
        assertEquals(1.75 - 0.25 - 0.5, flat.effectiveSpreadDown(1.75, 0.5), 1e-9);
    }

    // ---- yml の読み込み ----

    @Test
    @DisplayName("workbench/ritual 節を別々に読む")
    void workbenchAndRitualSectionsAreReadIndependently() {
        CraftQualityConfig config = configFrom("""
                workbench:
                  upswing-scale: 2.0
                  upswing-flat: 0.5
                  downswing-reduction-scale: 0.25
                  downswing-reduction-flat: 0.125
                ritual:
                  upswing-scale: 0.0
                  upswing-flat: -1.0
                  downswing-reduction-scale: 3.0
                  downswing-reduction-flat: 0.75
                """);

        assertEquals(new CraftQualityConfig.SpreadTuning(2.0, 0.5, 0.25, 0.125), config.workbenchSpread());
        assertEquals(new CraftQualityConfig.SpreadTuning(0.0, -1.0, 3.0, 0.75), config.ritualSpread());
    }

    @Test
    @DisplayName("節が丸ごと無い旧ymlは恒等(=分離前と同じ挙動)へ落ちる")
    void missingSectionsFallBackToIdentity() {
        CraftQualityConfig config = configFrom("mode:\n  skill-levels-per-quality: 10\n");
        assertEquals(CraftQualityConfig.SpreadTuning.IDENTITY, config.workbenchSpread());
        assertEquals(CraftQualityConfig.SpreadTuning.IDENTITY, config.ritualSpread());
    }

    @Test
    @DisplayName("出荷 stats/craft-quality.yml の既定値は恒等でなければならない(分離でバランスを動かさない契約)")
    void shippedDefaultsAreIdentity() throws Exception {
        try (InputStream in = CraftQualitySpreadSplitTest.class.getClassLoader()
                .getResourceAsStream("stats/craft-quality.yml")) {
            assertNotNull(in, "出荷リソース stats/craft-quality.yml が見つからない");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String section : new String[]{"workbench", "ritual"}) {
                ConfigurationSection node = yaml.getConfigurationSection(section);
                assertNotNull(node, section + " 節が出荷ymlに無い(editorから設定できない)");
            }
            CraftQualityConfig config = new CraftQualityConfig();
            config.apply(yaml);
            assertEquals(CraftQualityConfig.SpreadTuning.IDENTITY, config.workbenchSpread(),
                    "出荷既定値が恒等でない = 分離しただけでバランスが動いている");
            assertEquals(CraftQualityConfig.SpreadTuning.IDENTITY, config.ritualSpread(),
                    "出荷既定値が恒等でない = 分離しただけでバランスが動いている");
        }
    }

    // ---- 経路の振り分け ----

    @Test
    @DisplayName("儀式側だけ下振れ抑制を殺しても作業台のプレビュー下限は変わらない")
    void ritualTuningDoesNotLeakIntoTheWorkbenchPath() {
        Player player = mock(Player.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, Map.of(
                "workbench_quality_bonus", 4.0,
                "craft_downswing_reduction", 999.0));
        // ritual 側だけ「下振れ抑制ステを効かせない」に倒す。作業台側は恒等のまま。
        CraftQualityConfig config = configFrom("ritual:\n  downswing-reduction-scale: 0.0\n");
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, config, new QualityConfig(), aggregator, null);

        // 作業台プレビューの保証下限は従来通り「ステで下振れσが潰れて mode に張り付く」。
        assertEquals(4, service.minimumQuality(player, Set.of()));
    }

    @Test
    @DisplayName("作業台側の下振れ抑制を殺すとプレビュー下限が mode より下がる(経路設定が効いている)")
    void workbenchTuningChangesTheWorkbenchPreviewFloor() {
        Player player = mock(Player.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, Map.of(
                "workbench_quality_bonus", 4.0,
                "craft_downswing_reduction", 999.0));
        CraftQualityConfig config = configFrom("workbench:\n  downswing-reduction-scale: 0.0\n");
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, config, new QualityConfig(), aggregator, null);

        assertEquals(4, service.qualityMode(player, Set.of()), "mode 自体は変わらない");
        assertTrue(service.minimumQuality(player, Set.of()) < 4,
                "下振れ抑制ステを効かせない設定なら、下振れσが残るので下限は mode より下");
    }

    @Test
    @DisplayName("儀式ロールは上振れσが0未満へ落ちない(flat をやり過ぎても mode 固定で止まる)")
    void ritualRollStaysInRangeWithAggressiveFlat() {
        Player player = mock(Player.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, Map.of("ritual_quality_bonus", 3.0));
        CraftQualityConfig config = configFrom("ritual:\n  upswing-flat: -999.0\n");
        QualityConfig quality = new QualityConfig();
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, config, quality, aggregator, null);

        for (int i = 0; i < 200; i++) {
            int rolled = service.rollArsSmithingQuality(player);
            assertTrue(rolled >= 0 && rolled <= quality.maxQuality(),
                    "品質が範囲外: " + rolled);
            assertTrue(rolled <= 3, "上振れσ0なので mode(3) を超えないはず: " + rolled);
        }
    }
}
