package com.trinityforge.stats;

import com.trinityforge.command.StatsCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CraftQualityService} が読むステータスキーが、語彙・カテゴリ・出荷 yml から
 * ドリフトしていないことを機械照合する。
 *
 * <p>なぜ必要か: {@code CraftQualityService#readDoubleStat} は
 * {@code aggregator.totalOf(未知キー)} が <b>例外を投げず 0.0 を返す</b>ので、キー文字列を
 * 1文字打ち間違えても「そのステが恒久的に効かない」という形でしか現れない。production でも
 * テストでも症状が出ないため、名寄せ点({@link CraftQualityService#statKeysRead()})を1つ作って
 * ここで突き合わせるしかない。
 *
 * <p>経路分離({@code workbench} / {@code ritual})そのものの挙動は
 * {@code CraftQualitySpreadSplitTest} が出力分布で固定している。こちらは名前の整合だけを見る。
 *
 * <p><b>2026-08-01 の経緯</b>: 本ファイルは元々「経路ごとに別のステキーを持つ」設計
 * (work/w2b-stat-vocab-fix-g4)を前提にしていた。出荷実装は別案 ——
 * 「上振れ/下振れは経路共通のステのままで、経路差は {@code stats/craft-quality.yml} の
 * {@code workbench.*} / {@code ritual.*} 倍率で付ける」—— を採ったため、
 * 前提が食い違うアサーションは撤去した(実装と噛み合わないまま緑になるテストは有害)。
 * <b>「装備・パーク単位でも経路を分けたい」なら別設計が要る</b> —— この差は
 * {@code reports/ACTIVE_RECORD.md} に残してある。
 */
class CraftQualityPathSplitTest {

    private static ConfigurationSection section(String resource, String path) throws Exception {
        try (InputStream in = CraftQualityPathSplitTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, resource + " が classpath に無い");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection sec = yaml.getConfigurationSection(path);
            assertNotNull(sec, resource + " に " + path + " 節が無い");
            return sec;
        }
    }

    @Test
    @DisplayName("CraftQualityService が読む全キーが StatVocabulary に実在する")
    void everyStatKeyReadExistsInVocabulary() {
        Set<String> keys = CraftQualityService.statKeysRead();
        assertEquals(9, keys.size(),
                "読むキーの本数が変わっている(経路別 mode/上振れ/下振れ = 2×3 + 経路共通のロール3)。"
                        + "statKeysRead() の更新漏れでないか確認すること");
        Set<String> known = StatVocabulary.allKeys();
        for (String key : keys) {
            assertTrue(known.contains(key), key + " が StatVocabulary に無い(タイポの可能性)");
        }
    }

    @Test
    @DisplayName("読むキーは /tf stats craft に出て、base-stats.yml と lore.yml の両方に行がある")
    void everyStatKeyReadIsReachableFromConfigAndCommand() throws Exception {
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
}
