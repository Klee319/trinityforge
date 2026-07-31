package com.trinityforge.stats;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.command.StatsCategory;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 作業台経路と儀式経路が<b>本当に別のステキーを読んでいるか</b>を挙動レベルで固定する
 * (2026-07-31 L7-6 分割のレビュー指摘1)。
 *
 * <p>なぜ必要か: {@code CraftQualityService#readDoubleStat} は
 * {@code aggregator.totalOf(未知キー)} が 0.0 を返すだけで例外を投げないため、
 * {@code CraftQualityService.CraftPath} のキー文字列を1文字打ち間違えても、あるいは
 * {@code rollArsSmithingQuality} を {@code CraftPath.WORKBENCH} で呼ぶ形に戻しても、
 * <b>語彙・yml・editor辞書のドリフト検知テストは全部緑のまま</b>で
 * 「ars_smithing.yml の {@code ritual-upswing-bonus} が恒久的に死ぬ」/
 * 「鍛冶ツリーのパークが儀式クラフトへ漏れる」が無検知で再発する。
 *
 * <p>検証の作り: リフレクションを使わず公開 API ({@code rollQuality} / {@code rollArsSmithingQuality})
 * の<b>出力分布</b>だけで分岐を観測する。
 * <ul>
 *   <li><b>決定論側</b>: {@code *_quality_bonus} を巨大値にすると mode が maxQuality(既定9)へ張り付き、
 *       {@code *_downswing_reduction} を巨大値にすると下側σが 0 にクランプされる。両方入れると
 *       {@code resolveQualityNormal} は正規分布の符号に関わらず 9 を返す = 完全に決定論になる。</li>
 *   <li><b>非到達側</b>: 相手経路のキーだけを入れると mode=0 / σ=1.5 のままなので、
 *       9 に届くには標準正規が 5.67σ 以上必要(1回あたり約7e-9)。よって
 *       「200回引いて一度も 9 が出ない」を安全に表明できる。</li>
 * </ul>
 */
class CraftQualityPathSplitTest {

    /** QualityConfig() 既定の max-quality。決定論側の期待値。 */
    private static final int MAX_QUALITY = 9;
    private static final int DRAWS = 200;

    private static PlayerStatAggregator aggregatorReturning(Player player, Map<String, Double> stats) {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(
                new PlayerCombatAggregate(stats, Map.of(), Map.of(), Map.of(), Map.of()));
        return aggregator;
    }

    private static CraftQualityService serviceWith(Player player, Map<String, Double> stats) {
        return new CraftQualityService(SkillLevelSource.EMPTY, new CraftQualityConfig(),
                new QualityConfig(), aggregatorReturning(player, stats), null);
    }

    /** 「品質を最大へ張り付かせる」2キーの組(経路名を差し替えて使う)。 */
    private static Map<String, Double> pinToMax(String prefix) {
        return Map.of(prefix + "_quality_bonus", 999.0, prefix + "_downswing_reduction", 999.0);
    }

    // ---- 決定論側: 自分の経路のキーは効く ----

    @Test
    @DisplayName("儀式クラフトは ritual_* を読む(ritual の品質+下振れ抑制で結果が最大品質へ固定される)")
    void ritualPathReadsRitualKeys() {
        Player player = mock(Player.class);
        CraftQualityService service = serviceWith(player, pinToMax("ritual"));
        for (int i = 0; i < DRAWS; i++) {
            assertEquals(MAX_QUALITY, service.rollArsSmithingQuality(player),
                    "ritual_quality_bonus / ritual_downswing_reduction が儀式経路に効いていない");
        }
    }

    @Test
    @DisplayName("作業台クラフトは workbench_* を読む(workbench の品質+下振れ抑制で結果が最大品質へ固定される)")
    void workbenchPathReadsWorkbenchKeys() {
        Player player = mock(Player.class);
        CraftQualityService service = serviceWith(player, pinToMax("workbench"));
        for (int i = 0; i < DRAWS; i++) {
            assertEquals(MAX_QUALITY, service.rollQuality(player, Set.of()),
                    "workbench_quality_bonus / workbench_downswing_reduction が作業台経路に効いていない");
        }
    }

    // ---- 非到達側: 相手の経路のキーは効かない(漏れの検知) ----

    @Test
    @DisplayName("作業台のパークは儀式クラフトへ漏れない")
    void workbenchKeysDoNotLeakIntoRitual() {
        Player player = mock(Player.class);
        CraftQualityService service = serviceWith(player, pinToMax("workbench"));
        for (int i = 0; i < DRAWS; i++) {
            assertTrue(service.rollArsSmithingQuality(player) < MAX_QUALITY,
                    "workbench_* が儀式クラフトの品質に効いている(鍛冶ツリーのパークが儀式へ漏れている)");
        }
    }

    @Test
    @DisplayName("儀式のパークは作業台クラフトへ漏れない")
    void ritualKeysDoNotLeakIntoWorkbench() {
        Player player = mock(Player.class);
        CraftQualityService service = serviceWith(player, pinToMax("ritual"));
        for (int i = 0; i < DRAWS; i++) {
            assertTrue(service.rollQuality(player, Set.of()) < MAX_QUALITY,
                    "ritual_* が作業台クラフトの品質に効いている(魔法鍛冶ツリーのパークが作業台へ漏れている)");
        }
    }

    // ---- 上振れ拡大キーも両方向で見る(ars_smithing.yml の ritual-upswing-bonus が死ぬ経路) ----

    @Test
    @DisplayName("ritual_upswing_bonus は儀式の上振れを広げ、workbench_upswing_bonus は広げない")
    void upswingKeysAreRoutedPerPath() {
        Player player = mock(Player.class);
        // 上側σだけを巨大化する。mode=0 のままなので、上振れが効いていれば約半分の抽選が maxQuality へ届く。
        CraftQualityService ritual = serviceWith(player, Map.of("ritual_upswing_bonus", 999.0));
        boolean ritualReachedMax = false;
        for (int i = 0; i < DRAWS && !ritualReachedMax; i++) {
            ritualReachedMax = ritual.rollArsSmithingQuality(player) == MAX_QUALITY;
        }
        assertTrue(ritualReachedMax,
                "ritual_upswing_bonus が儀式クラフトの上振れσに効いていない(ars_smithing.yml の宣言が死ぬ)");

        Player other = mock(Player.class);
        CraftQualityService workbench = serviceWith(other, Map.of("workbench_upswing_bonus", 999.0));
        for (int i = 0; i < DRAWS; i++) {
            assertTrue(workbench.rollArsSmithingQuality(other) < MAX_QUALITY,
                    "workbench_upswing_bonus が儀式クラフトの上振れσに効いている(漏れ)");
        }
    }

    @Test
    @DisplayName("workbench_upswing_bonus は作業台の上振れを広げ、ritual_upswing_bonus は広げない")
    void upswingKeysAreRoutedPerPathForWorkbench() {
        Player player = mock(Player.class);
        CraftQualityService workbench = serviceWith(player, Map.of("workbench_upswing_bonus", 999.0));
        boolean reachedMax = false;
        for (int i = 0; i < DRAWS && !reachedMax; i++) {
            reachedMax = workbench.rollQuality(player, Set.of()) == MAX_QUALITY;
        }
        assertTrue(reachedMax, "workbench_upswing_bonus が作業台クラフトの上振れσに効いていない");

        Player other = mock(Player.class);
        CraftQualityService ritual = serviceWith(other, Map.of("ritual_upswing_bonus", 999.0));
        for (int i = 0; i < DRAWS; i++) {
            assertTrue(ritual.rollQuality(other, Set.of()) < MAX_QUALITY,
                    "ritual_upswing_bonus が作業台クラフトの上振れσに効いている(漏れ)");
        }
    }

    // ---- キー文字列の機械照合(タイポで無言に 0 になるのを防ぐ) ----

    @Test
    @DisplayName("CraftQualityService が読む全キーが StatVocabulary の GENERAL に実在する")
    void everyStatKeyReadExistsInVocabulary() {
        Set<String> keys = CraftQualityService.statKeysRead();
        assertEquals(9, keys.size(), "読むキーの本数が変わっている(経路2×3 + ロール3)");
        Set<String> known = StatVocabulary.allKeys();
        for (String key : keys) {
            assertTrue(known.contains(key),
                    key + " が StatVocabulary に無い(タイポなら aggregator.totalOf が無言で 0.0 を返す)");
            assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf(key),
                    key + " が GENERAL チャネルではない");
            assertEquals(key, StatKeys.canonical(key), key + " が canonical 形(snake_case)でない");
        }
    }

    @Test
    @DisplayName("CraftQualityService が読む全キーが StatsCategory.CRAFT と出荷 yml の両方に居る")
    void everyStatKeyReadIsDeclaredInShippedConfigs() throws Exception {
        ConfigurationSection baseStats = section("combat/base-stats.yml", "base-stats");
        ConfigurationSection lore = section("stats/lore.yml", "stats");
        for (String key : CraftQualityService.statKeysRead()) {
            String kebab = key.replace('_', '-');
            assertTrue(StatsCategory.CRAFT.includes(key),
                    key + " が StatsCategory.CRAFT に無い(/tf stats craft に出ない)");
            assertTrue(baseStats.contains(kebab), kebab + " が combat/base-stats.yml に無い");
            assertTrue(lore.contains(kebab), kebab + " が stats/lore.yml に無い");
        }
    }

    @Test
    @DisplayName("経路ごとのキーは接頭辞まで含めて取り違えていない")
    void pathKeysCarryTheirOwnPrefix() {
        for (CraftQualityService.CraftPath path : CraftQualityService.CraftPath.values()) {
            String prefix = path.name().toLowerCase(java.util.Locale.ROOT) + "_";
            for (String key : Set.of(path.qualityBonusKey(), path.upswingKey(), path.downswingKey())) {
                assertTrue(key.startsWith(prefix), path + " のキー " + key + " が接頭辞 " + prefix + " で始まらない");
            }
        }
        // ロール3キーは分割していない(PDC焼込みのため)。接頭辞が経路名に化けていないこと。
        assertFalse(CraftQualityService.ROLL_UP_BONUS_KEY.startsWith("workbench_"));
        assertFalse(CraftQualityService.ROLL_UP_BONUS_KEY.startsWith("ritual_"));
    }

    private static ConfigurationSection section(String resource, String path) throws Exception {
        try (InputStream in = CraftQualityPathSplitTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "出荷リソース " + resource + " が見つからない");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection s = yaml.getConfigurationSection(path);
            assertNotNull(s, resource + " に " + path + ": セクションが無い");
            return s;
        }
    }
}
