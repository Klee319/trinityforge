package com.trinityforge.stats;

import com.trinityforge.command.StatsCategory;
import com.trinityforge.progression.core.SkillId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「職業EXP増加(スキル別)」ステが<b>全スキル分そろっている</b>ことの回帰テスト (2026-08-05)。
 *
 * <p>背景: 実サーバ報告「職業EXP増加のステータスの種類が、総合と、掘削、農業、伐採しかない」。
 * 2026-08-02 に新設したとき、消費側（{@code PerSkillExpBonus} は
 * {@code canonical(skillId + "_exp_bonus")} を機械的に引く）は全スキル対応だったのに、
 * <b>語彙・%矯正・分類・lore の4箇所へ3件だけ手書き</b>していたため、残り12スキルは
 * 「yml に書いても語彙に無い＝警告が出て効かない」状態だった。
 *
 * <p>ここで固定するのは以下の4点。どれか1つでも欠けると、そのスキルだけ無言で壊れる:
 * <ol>
 *   <li>{@link StatVocabulary} に GENERAL チャネルとして登録されている（登録漏れ＝合算されない）</li>
 *   <li>{@link PercentStatNormalize} の率キーである（漏れると yml の {@code 15} が 1500% になる）</li>
 *   <li>{@link StatCategoryInference} が UTILITY を返す（{@code armor} 等の部分一致に先取りされない）</li>
 *   <li>{@link StatsCategory} の UTILITY に載っている（{@code /tf stats} のタブから落ちない）</li>
 * </ol>
 *
 * <p>加えて「出荷 {@code stats/lore.yml} に表示定義がある」ことも見る。lore に無いキーは
 * ステータス画面へ一切出ないため、実装が正しくてもプレイヤーからは存在しないのと同じになる。
 */
class SkillExpBonusKeysTest {

    @Test
    @DisplayName("POWER 以外の15スキル分そろっており、POWER は作られていない")
    void coversEverySkillExceptPower() {
        Set<String> keys = SkillExpBonusKeys.all();
        assertEquals(SkillId.ALL.size() - 1, keys.size(),
                "スキル数 - 1(POWER) と一致しない: " + keys);
        for (String skillId : SkillId.ALL) {
            String key = StatKeys.canonical(skillId + SkillExpBonusKeys.SUFFIX);
            if (SkillId.POWER.equals(skillId)) {
                assertFalse(keys.contains(key),
                        "power_exp_bonus は作ってはいけない(POWER EXP は他スキルのレベルアップの"
                                + "副作用として倍率適用より後段で加算されるため一度も読まれない)");
                continue;
            }
            assertTrue(keys.contains(key), skillId + " 分のキーが無い: " + key);
        }
        // 2026-08-02 時点の3件が消えていないこと(改名・巻き込み削除の検出)。
        assertTrue(keys.contains("woodcutting_exp_bonus"), keys.toString());
        assertTrue(keys.contains("farming_exp_bonus"), keys.toString());
        assertTrue(keys.contains("digging_exp_bonus"), keys.toString());
    }

    @Test
    @DisplayName("全キーが 語彙GENERAL / 率キー / UTILITY分類 の3条件を満たす")
    void everyKeyIsRegisteredInAllFourPlaces() {
        List<String> problems = new ArrayList<>();
        for (String key : SkillExpBonusKeys.all()) {
            if (StatVocabulary.channelOf(key) != StatVocabulary.Channel.GENERAL) {
                problems.add(key + ": StatVocabulary 未登録(channel="
                        + StatVocabulary.channelOf(key) + ") → パーク/装備から合算されない");
            }
            if (!PercentStatNormalize.isRateKey(key)) {
                problems.add(key + ": PercentStatNormalize 未登録 → yml の 15 が 1500% になる");
            }
            if (StatCategoryInference.infer(key) != StatCategory.UTILITY) {
                problems.add(key + ": 分類が UTILITY でない("
                        + StatCategoryInference.infer(key) + ")");
            }
            if (!StatsCategory.UTILITY.includes(key)) {
                problems.add(key + ": /tf stats の UTILITY タブに出ない");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("%矯正が効いている(15 と書いたら +15% になる)")
    void percentPointsAreCoerced() {
        for (String key : SkillExpBonusKeys.all()) {
            assertEquals(0.15, PercentStatNormalize.coerce(key, 15), 1e-9, key);
            assertEquals(0.05, PercentStatNormalize.coerce(key, 0.05), 1e-9, key + " (既に分数)");
        }
    }

    @Test
    @DisplayName("出荷 stats/lore.yml に全キーの表示定義がある")
    void shippedLoreYmlDeclaresEveryKey() throws Exception {
        String lore;
        try (InputStream in = SkillExpBonusKeysTest.class.getClassLoader()
                .getResourceAsStream("stats/lore.yml")) {
            assertNotNull(in, "出荷リソース stats/lore.yml が見つからない");
            lore = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 空振り防止: 2026-08-02 から存在するキーで読み込み自体が成立していることを確認する。
        assertTrue(lore.contains("woodcutting-exp-bonus:"),
                "lore.yml を読めていない(この照合は空振りしている)");

        List<String> missing = new ArrayList<>();
        for (String key : SkillExpBonusKeys.all()) {
            String ymlKey = key.replace('_', '-') + ":";
            if (!lore.contains(ymlKey)) {
                missing.add(ymlKey);
            }
        }
        assertTrue(missing.isEmpty(),
                "stats/lore.yml に表示定義が無いキー(ステータス画面に一切出ない): " + missing);
    }
}
