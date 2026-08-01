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
 * 2026-08-01 分離: 品質抽選の「ばらつき」補正(上振れ増加 {@code workbench_upswing_bonus} /
 * 下振れ抑制 {@code workbench_downswing_reduction})を、作業台クラフトと儀式(ArsPaperのリチュアル)で
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

    /** 分離前の上振れσ: {@code spread-up + max(0, ステ)}(クランプ無し)。 */
    private static double preSplitSpreadUp(double base, double statBonus) {
        return base + Math.max(0.0, statBonus);
    }

    /** 分離前の下振れσ: {@code max(0, spread-down - max(0, ステ))}(クランプは分離前からある)。 */
    private static double preSplitSpreadDown(double base, double statReduction) {
        return Math.max(0.0, base - Math.max(0.0, statReduction));
    }

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

    @Test
    @DisplayName("恒等は『config から到達できる入力』では分離前と一致し、差が出るのは負のbaseだけ(新設の0クランプ)")
    void identityMatchesThePreSplitFormulaForEveryReachableInput() {
        CraftQualityConfig.SpreadTuning identity = CraftQualityConfig.SpreadTuning.IDENTITY;

        // spread-up / spread-down は QualityConfig が 0 未満へ落とさない = base は必ず 0 以上。
        // その範囲では上振れ側の新設クランプは発火せず、分離前の式と1ミリも違わない。
        for (double base : new double[]{0.0, 0.25, 1.5, 1.75, 10.0}) {
            for (double stat : new double[]{-5.0, 0.0, 0.3, 2.0, 999.0}) {
                assertEquals(preSplitSpreadUp(base, stat), identity.effectiveSpreadUp(base, stat), 1e-9,
                        "base=" + base + " stat=" + stat);
                assertEquals(preSplitSpreadDown(base, stat), identity.effectiveSpreadDown(base, stat), 1e-9,
                        "base=" + base + " stat=" + stat);
            }
        }

        // 唯一の差: 負の base。分離前は負のσをそのまま返していたが、今は 0 で止まる。
        // これは意図的な変更で、config からは作れない入力(下の testで裏を取る)。
        assertEquals(-1.0, preSplitSpreadUp(-1.0, 0.0), 1e-9);
        assertEquals(0.0, identity.effectiveSpreadUp(-1.0, 0.0), 1e-9,
                "新設した0クランプ: σが負に振り切れるのを止める");
    }

    @Test
    @DisplayName("負の spread-up は config から作れない(=上振れの0クランプは既定値では発火しない)")
    void qualityConfigNeverHandsOutANegativeSpread() {
        QualityConfig quality = new QualityConfig();
        assertTrue(quality.spreadUp() >= 0.0, "spread-up が負になると新設クランプが挙動差になる");
        assertTrue(quality.spreadDown() >= 0.0);
    }

    @Test
    @DisplayName("upswing-flat に負値を書いてもσは0で止まる(0クランプを新設した理由そのもの)")
    void negativeUpswingFlatIsClampedInsteadOfGoingNegative() {
        CraftQualityConfig config = configFrom("ritual:\n  upswing-flat: -999.0\n");
        assertEquals(0.0, config.ritualSpread().effectiveSpreadUp(1.75, 5.0), 1e-9);
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
                "workbench_downswing_reduction", 999.0));
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
                "workbench_downswing_reduction", 999.0));
        CraftQualityConfig config = configFrom("workbench:\n  downswing-reduction-scale: 0.0\n");
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, config, new QualityConfig(), aggregator, null);

        assertEquals(4, service.qualityMode(player, Set.of()), "mode 自体は変わらない");
        assertTrue(service.minimumQuality(player, Set.of()) < 4,
                "下振れ抑制ステを効かせない設定なら、下振れσが残るので下限は mode より下");
    }

    // ---- S7: 品質mode加算ステ(workbench_quality_bonus / ritual_quality_bonus)の経路分割 ----

    /**
     * 両経路のσを 0 に潰す設定。ロール結果が mode そのものになるので、
     * 「どちらの mode 加算ステを読んだか」を乱数抜きで観測できる。
     */
    private static final String PINNED_TO_MODE = """
            workbench:
              upswing-flat: -999.0
              downswing-reduction-flat: 999.0
            ritual:
              upswing-flat: -999.0
              downswing-reduction-flat: 999.0
            """;

    private static CraftQualityService serviceWith(Player player, Map<String, Double> stats) {
        return new CraftQualityService(SkillLevelSource.EMPTY, configFrom(PINNED_TO_MODE),
                new QualityConfig(), aggregatorReturning(player, stats), null);
    }

    @Test
    @DisplayName("S7: workbench_quality_bonus は作業台ロールにだけ乗る(儀式へは漏れない)")
    void workbenchQualityBonusOnlyAffectsTheWorkbenchRoll() {
        Player player = mock(Player.class);
        CraftQualityService service = serviceWith(player, Map.of("workbench_quality_bonus", 4.0));

        assertEquals(4, service.rollQuality(player, Set.of()), "作業台側には乗る");
        assertEquals(0, service.rollArsSmithingQuality(player), "儀式側には乗らない");
    }

    @Test
    @DisplayName("S7: ritual_quality_bonus は儀式ロールにだけ乗る(作業台へは漏れない)")
    void ritualQualityBonusOnlyAffectsTheRitualRoll() {
        Player player = mock(Player.class);
        CraftQualityService service = serviceWith(player, Map.of("ritual_quality_bonus", 3.0));

        assertEquals(0, service.rollQuality(player, Set.of()), "作業台側には乗らない");
        assertEquals(3, service.rollArsSmithingQuality(player), "儀式側には乗る");
    }

    @Test
    @DisplayName("S7: 両方持っていても互いに足し合わされず、経路ごとに独立して効く")
    void bothQualityBonusesStayIndependent() {
        Player player = mock(Player.class);
        CraftQualityService service = serviceWith(player, Map.of(
                "workbench_quality_bonus", 4.0,
                "ritual_quality_bonus", 3.0));

        assertEquals(4, service.rollQuality(player, Set.of()), "合算(7)になってはいけない");
        assertEquals(3, service.rollArsSmithingQuality(player), "合算(7)になってはいけない");
        assertEquals(4, service.qualityMode(player, Set.of()), "プレビューは作業台側");
    }

    // ---- 「効かないステは lore からも消す」ための無効化キー ----

    @Test
    @DisplayName("既定(scale=1.0)ではどのステも無効化されていない = lore から消さない")
    void nothingIsInertWithTheShippedDefaults() {
        assertEquals(Set.of(), configFrom("mode:\n  base-quality: 0\n").inertSpreadStatKeys());
    }

    @Test
    @DisplayName("scale=0 にした経路のステだけが無効化キーになる(もう片方の経路のキーは残る)")
    void onlyStatsMutedOnTheirOwnPathAreReportedAsInert() {
        // 2026-08-01 の経路別キー分割後は 1キーが1経路にしか属さないので、
        // 「作業台だけ 0」にしたら作業台側のキーだけが inert になる。
        // (共通キー時代は「両経路とも 0 のときだけ」だったが、その条件のまま経路別キーへ
        //  当てると、作業台で死んだステが儀式が生きている限り lore に出続ける取りこぼしになる。)
        CraftQualityConfig workbenchOnly = configFrom("workbench:\n  upswing-scale: 0.0\n");
        assertEquals(Set.of(CraftQualityConfig.WORKBENCH_UPSWING_STAT_KEY),
                workbenchOnly.inertSpreadStatKeys(),
                "儀式側のキーは効いたままなので隠してはいけない");

        CraftQualityConfig bothPathsUpswingDead = configFrom("""
                workbench:
                  upswing-scale: 0.0
                ritual:
                  upswing-scale: 0.0
                """);
        assertEquals(
                Set.of(CraftQualityConfig.WORKBENCH_UPSWING_STAT_KEY,
                        CraftQualityConfig.RITUAL_UPSWING_STAT_KEY),
                bothPathsUpswingDead.inertSpreadStatKeys());

        CraftQualityConfig allDead = configFrom("""
                workbench:
                  upswing-scale: 0.0
                  downswing-reduction-scale: 0.0
                ritual:
                  upswing-scale: 0.0
                  downswing-reduction-scale: 0.0
                """);
        assertEquals(
                Set.of(CraftQualityConfig.WORKBENCH_UPSWING_STAT_KEY,
                        CraftQualityConfig.RITUAL_UPSWING_STAT_KEY,
                        CraftQualityConfig.WORKBENCH_DOWNSWING_STAT_KEY,
                        CraftQualityConfig.RITUAL_DOWNSWING_STAT_KEY),
                allDead.inertSpreadStatKeys());
    }

    @Test
    @DisplayName("flat だけ入れても『ステが効く』ことにはならない(判定は scale だけを見る)")
    void flatDoesNotResurrectAMutedStat() {
        CraftQualityConfig config = configFrom("""
                workbench:
                  upswing-scale: 0.0
                  upswing-flat: 5.0
                ritual:
                  upswing-scale: 0.0
                  upswing-flat: 5.0
                """);
        assertEquals(
                Set.of(CraftQualityConfig.WORKBENCH_UPSWING_STAT_KEY,
                        CraftQualityConfig.RITUAL_UPSWING_STAT_KEY),
                config.inertSpreadStatKeys());
    }

    @Test
    @DisplayName("無効化キーは実際に読み出しているステキーと一致する(名前がズレたら無言で外れる)")
    void inertKeysMatchTheKeysTheServiceActuallyReads() {
        Player player = mock(Player.class);
        // 上振れステだけを持たせ、上振れσが実際に広がることで「このキーが読まれている」ことを示す。
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, configFrom("ritual:\n  downswing-reduction-flat: 999.0\n"),
                new QualityConfig(),
                // Ars鍛冶(儀式)経路のロールなので、儀式側のキーを持たせる。
                aggregatorReturning(player, Map.of(CraftQualityConfig.RITUAL_UPSWING_STAT_KEY, 50.0)), null);

        boolean sawAboveMode = false;
        for (int i = 0; i < 200 && !sawAboveMode; i++) {
            sawAboveMode = service.rollArsSmithingQuality(player) > 0;
        }
        assertTrue(sawAboveMode,
                "CraftQualityConfig.RITUAL_UPSWING_STAT_KEY が CraftQualityService の読み出しキーと一致していない");
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
