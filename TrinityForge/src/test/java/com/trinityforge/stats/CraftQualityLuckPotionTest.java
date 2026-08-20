package com.trinityforge.stats;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.progression.SkillLevelSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ユーザー要望 (2026-08-20)「醸造・作業台・儀式の各品質ptも幸運のポーションレベルに応じて上がるように」の
 * うち<b>作業台クラフト・儀式クラフト</b>側の回帰テスト（醸造側は {@code PotionQualityListenerTest}）。
 *
 * <p>換算レートは {@code stats/quality.yml} の {@code luck-potion-quality-per-level}（既定 1.0 ＝ 幸運I で +1）。
 * 幸運の効果レベル読み取りは {@link VanillaLuckEffect} に一本化してあり、釣り・拾得の
 * {@link PlayerLootLuckSource}（{@code loot_luck} へ常に 1レベル=+1.0）とは<b>別経路</b>である点に注意。
 *
 * <p>ロールを mode に釘付けするため、{@code craft-quality.yml} のばらつきを両経路とも殺した config を使う
 * （{@code CraftQualitySpreadSplitTest} と同じ手口）。こうしないとガウス抽選が入って期待値を固定できない。
 */
class CraftQualityLuckPotionTest {

    /**
     * 両経路のσを 0 に潰す設定（{@code CraftQualitySpreadSplitTest} と同一）。
     * ロール結果が mode そのものになるので、乱数抜きで「品質ptがいくつ乗ったか」を観測できる。
     */
    private static final String PINNED_TO_MODE = """
            workbench:
              upswing-flat: -999.0
              downswing-reduction-flat: 999.0
            ritual:
              upswing-flat: -999.0
              downswing-reduction-flat: 999.0
            """;

    private static PlayerStatAggregator aggregatorReturning(Player player, Map<String, Double> stats) {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(
                new PlayerCombatAggregate(stats, Map.of(), Map.of(), Map.of(), Map.of()));
        return aggregator;
    }

    private static CraftQualityConfig pinnedCraftConfig() {
        CraftQualityConfig config = new CraftQualityConfig();
        config.apply(YamlConfiguration.loadConfiguration(new StringReader(PINNED_TO_MODE)));
        return config;
    }

    /** 幸運 {@code level} レベルを飲んでいるプレイヤー（0 = 飲んでいない）。 */
    private static Player playerWithLuck(int level) {
        Player player = mock(Player.class);
        when(player.getPotionEffect(PotionEffectType.LUCK)).thenReturn(
                level <= 0 ? null : new PotionEffect(PotionEffectType.LUCK, 1200, level - 1));
        return player;
    }

    private static CraftQualityService serviceFor(Player player, Map<String, Double> stats,
                                                  QualityConfig quality) {
        return new CraftQualityService(SkillLevelSource.EMPTY, pinnedCraftConfig(), quality,
                aggregatorReturning(player, stats), null);
    }

    @Test
    @DisplayName("幸運のポーションぶんが作業台クラフトの品質modeへ乗る")
    void luckPotionRaisesWorkbenchQuality() {
        Player player = playerWithLuck(2);
        CraftQualityService service = serviceFor(player, Map.of(), new QualityConfig());

        assertEquals(2, service.rollQuality(player, Set.of()), "幸運II = 品質+2");
    }

    @Test
    @DisplayName("幸運のポーションぶんが儀式クラフトの品質modeへ乗る")
    void luckPotionRaisesRitualQuality() {
        Player player = playerWithLuck(2);
        CraftQualityService service = serviceFor(player, Map.of(), new QualityConfig());

        assertEquals(2, service.rollArsSmithingQuality(player), "幸運II = 品質+2");
    }

    @Test
    @DisplayName("幸運ぶんは既存の品質ステへ加算される(置き換えではない)")
    void luckPotionAddsOnTopOfTheExistingStats() {
        Player player = playerWithLuck(1);
        CraftQualityService service = serviceFor(player, Map.of(
                "workbench_quality_bonus", 3.0,
                "ritual_quality_bonus", 2.0), new QualityConfig());

        assertEquals(4, service.rollQuality(player, Set.of()), "作業台 3 + 幸運I 1");
        assertEquals(3, service.rollArsSmithingQuality(player), "儀式 2 + 幸運I 1");
    }

    /**
     * 丸めは「ステ + 幸運」を<b>合計してから 1 回だけ</b>。別々に丸めると 0.5 + 0.5 が 0 + 0 になり、
     * 幸運を飲んでも何も起きない帯ができる（気づけないので明示的に固定する）。
     */
    @Test
    @DisplayName("端数は合計してから丸める(別々に丸めて消えない)")
    void fractionsAreRoundedOnceAfterSumming() {
        Player player = playerWithLuck(1);
        QualityConfig quality = mock(QualityConfig.class);
        when(quality.luckPotionQualityPerLevel()).thenReturn(0.5);
        when(quality.maxQuality()).thenReturn(9);
        when(quality.spreadUp()).thenReturn(0.0);
        when(quality.spreadDown()).thenReturn(0.0);
        CraftQualityService service = serviceFor(player, Map.of("workbench_quality_bonus", 0.5), quality);

        assertEquals(1, service.rollQuality(player, Set.of()), "0.5 + 0.5 = 1 (0 + 0 ではない)");
    }

    @Test
    @DisplayName("プレビュー(qualityMode / minimumQuality)にも同じだけ乗る")
    void previewReflectsTheLuckPotionToo() {
        Player player = playerWithLuck(2);
        CraftQualityService service = serviceFor(player, Map.of(), new QualityConfig());

        assertEquals(2, service.qualityMode(player, Set.of()), "プレビューの mode");
        assertEquals(2, service.minimumQuality(player, Set.of()), "プレビューの最低保証");
    }

    @Test
    @DisplayName("luck-potion-quality-per-level: 0 なら幸運は一切効かない(機能を切れる)")
    void luckPotionCanBeDisabledByConfig() {
        Player player = playerWithLuck(3);
        QualityConfig quality = mock(QualityConfig.class);
        when(quality.luckPotionQualityPerLevel()).thenReturn(0.0);
        when(quality.maxQuality()).thenReturn(9);
        when(quality.spreadUp()).thenReturn(0.0);
        when(quality.spreadDown()).thenReturn(0.0);
        CraftQualityService service = serviceFor(player, Map.of(), quality);

        assertEquals(0, service.rollQuality(player, Set.of()));
        assertEquals(0, service.rollArsSmithingQuality(player));
    }

    @Test
    @DisplayName("幸運を飲んでいなければ従来どおり(既存の挙動を1ミリも動かさない)")
    void withoutTheLuckPotionNothingChanges() {
        Player player = playerWithLuck(0);
        CraftQualityService service = serviceFor(player, Map.of("workbench_quality_bonus", 3.0),
                new QualityConfig());

        assertEquals(3, service.rollQuality(player, Set.of()));
        assertEquals(0, service.rollArsSmithingQuality(player));
    }
}
