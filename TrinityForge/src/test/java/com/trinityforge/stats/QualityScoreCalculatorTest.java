package com.trinityforge.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * スコア = 実際に付与された各randomステの正規化ロール位置
 * {@code (actual - min) / (max - min)} の単純平均 × 100。
 */
class QualityScoreCalculatorTest {

    private static final QualityRollModel NO_SPREAD_MODEL =
            new QualityRollModel(10, 0.0, 0.0, 0.0);

    @Test
    @DisplayName("fixed/per-qualityだけでrandomロールが無いアイテムは0点")
    void profileWithoutRandomRollsScoresZero() {
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-power", 10.0), Map.of("crit-chance", 2.0), Map.of());
        assertEquals(0, QualityScoreCalculator.score(
                profile, 10, 123L, NO_SPREAD_MODEL, null));
    }

    @Test
    @DisplayName("決定的なロール実値をmin/max内の位置として採点する")
    void scoresDeterministicRollPosition() {
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-power", 100.0), Map.of("attack-power", 5.0),
                Map.of("attack-power", new StatRange(10.0, 20.0)));
        assertEquals(30, QualityScoreCalculator.score(
                profile, 3, 123L, NO_SPREAD_MODEL, null));
    }

    @Test
    @DisplayName("複数ロールはステータス値の大小で重み付けせず正規化スコアを単純平均する")
    void averagesNormalizedScoresPerRolledStat() {
        int score = QualityScoreCalculator.scoreFromActualRolls(
                Map.of("attack-power", 2.5, "crit-chance", 35.0),
                Map.of("attack-power", new StatRange(0.0, 10.0),
                        "crit-chance", new StatRange(20.0, 40.0)),
                null);
        assertEquals(50, score);
    }

    @Test
    @DisplayName("付与されなかったrandomステは平均の分母にも含めない")
    void excludesRandomStatsThatWereNotGranted() {
        int score = QualityScoreCalculator.scoreFromActualRolls(
                Map.of("attack-power", 2.5, "crit-chance", 40.0),
                Map.of("attack-power", new StatRange(0.0, 10.0),
                        "crit-chance", new StatRange(20.0, 40.0)),
                Set.of("attack-power"));
        assertEquals(25, score);
    }

    @Test
    @DisplayName("範囲外実値は0〜100にクランプし、幅ゼロの定数rangeは除外する")
    void clampsEachRollAndExcludesZeroWidthRanges() {
        int score = QualityScoreCalculator.scoreFromActualRolls(
                Map.of("low", -5.0, "high", 15.0, "constant", 7.0),
                Map.of("low", new StatRange(0.0, 10.0),
                        "high", new StatRange(0.0, 10.0),
                        "constant", new StatRange(7.0, 7.0)),
                null);
        assertEquals(50, score);
    }
}
