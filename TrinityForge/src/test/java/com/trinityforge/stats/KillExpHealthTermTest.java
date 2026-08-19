package com.trinityforge.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 討伐EXPの最大HP項に入れた指数の回帰テスト(2026-08-19 ユーザー要望)。
 *
 * <p>固定する不変条件:
 * <ol>
 *   <li><b>anchor 以下のHP帯は1ミリも変わらない</b>(「序盤の上がり方は変えたくない」)。
 *       {@code min} を外して指数だけにすると<b>逆に増える</b>のでここが本質。</li>
 *   <li>Lv80 エンダーマン相当のHPで<b>約0.44倍</b>(報告値 8000〜10000 → 約4000)。</li>
 *   <li>anchor / exponent の未設定・不正値は<b>従来どおりの線形</b>(後方互換)。</li>
 * </ol>
 */
class KillExpHealthTermTest {

    /** 出荷 stats/skill-exp.yml の値。 */
    private static final double PER_MAX_HEALTH = 0.188;
    private static final double ANCHOR = 8000.0;
    private static final double EXPONENT = 0.74;

    /** combat/mob-types.yml の ENDERMAN を ConversionPolicy.Ramp で解いた値。 */
    private static double endermanHealth(int level) {
        double value = 1120.0 * Math.pow(1.053, level);
        if (level >= 45) {
            value += 3233.0 * (level - 45);
        }
        return value;
    }

    private static double shipped(double health) {
        return KillExpHealthTerm.healthTerm(PER_MAX_HEALTH, health, ANCHOR, EXPONENT);
    }

    private static double linear(double health) {
        return PER_MAX_HEALTH * health;
    }

    @Test
    @DisplayName("anchor 以下のHPは線形と完全に一致する(序盤の上がり方は不変)")
    void keepsEarlyLevelsExactlyAsBefore() {
        for (int level = 0; level <= 38; level++) {
            double health = endermanHealth(level);
            assertTrue(health <= ANCHOR,
                    "Lv" + level + " のHPが anchor を超えている(前提が崩れた): " + health);
            assertEquals(linear(health), shipped(health), 1e-9,
                    "Lv" + level + " のHP項が変わってしまっている");
        }
        // 20HP のバニラ相当・1HP の極小値も素通し。
        assertEquals(linear(20.0), shipped(20.0), 1e-9);
        assertEquals(linear(1.0), shipped(1.0), 1e-9);
    }

    @Test
    @DisplayName("Lv80 エンダーマン相当で約0.44倍、Lv100 でも0.3倍を下回らない")
    void compressesTheHighLevelBandToRoughlyFourTenths() {
        double ratio80 = shipped(endermanHealth(80)) / linear(endermanHealth(80));
        assertTrue(ratio80 > 0.40 && ratio80 < 0.48,
                "Lv80 の倍率が狙い(約0.44)から外れている: " + ratio80);

        double ratio100 = shipped(endermanHealth(100)) / linear(endermanHealth(100));
        assertTrue(ratio100 > 0.30 && ratio100 < ratio80,
                "Lv100 の倍率が壊れている(Lv80より小さく、かつ0.3倍以上であるべき): " + ratio100);

        // 単調増加であること(高レベルほどEXPが減る、という逆転を作らない)。
        double previous = 0.0;
        for (int level = 0; level <= 100; level += 5) {
            double current = shipped(endermanHealth(level));
            assertTrue(current > previous,
                    "Lv" + level + " でHP項が前の帯より減っている(圧縮しすぎ): " + current);
            previous = current;
        }
    }

    @Test
    @DisplayName("anchor / exponent の未設定・不正値は従来どおりの線形")
    void fallsBackToLinearWhenDisabledOrMalformed() {
        double health = endermanHealth(80);
        assertEquals(linear(health), KillExpHealthTerm.healthTerm(PER_MAX_HEALTH, health, 0.0, 0.74),
                1e-9, "anchor 0 は無効化(線形)であるべき");
        assertEquals(linear(health), KillExpHealthTerm.healthTerm(PER_MAX_HEALTH, health, -1.0, 0.74),
                1e-9, "負の anchor は無効化(線形)であるべき");
        assertEquals(linear(health), KillExpHealthTerm.healthTerm(PER_MAX_HEALTH, health, ANCHOR, 1.0),
                1e-9, "exponent 1.0 は無効化(線形)であるべき");
        assertEquals(linear(health), KillExpHealthTerm.healthTerm(PER_MAX_HEALTH, health, ANCHOR, 0.0),
                1e-9, "exponent 0 は「HP項が定数になる」ので無効化(線形)であるべき");
        assertEquals(linear(health),
                KillExpHealthTerm.healthTerm(PER_MAX_HEALTH, health, Double.NaN, 0.74), 1e-9);
        assertEquals(linear(health),
                KillExpHealthTerm.healthTerm(PER_MAX_HEALTH, health, ANCHOR, Double.NaN), 1e-9);
    }

    @Test
    @DisplayName("退化した入力は0(EXPを生まない)")
    void degenerateInputsProduceNothing() {
        assertEquals(0.0, KillExpHealthTerm.healthTerm(PER_MAX_HEALTH, 0.0, ANCHOR, EXPONENT));
        assertEquals(0.0, KillExpHealthTerm.healthTerm(PER_MAX_HEALTH, -5.0, ANCHOR, EXPONENT));
        assertEquals(0.0, KillExpHealthTerm.healthTerm(PER_MAX_HEALTH, Double.NaN, ANCHOR, EXPONENT));
        assertEquals(0.0, KillExpHealthTerm.healthTerm(0.0, 1000.0, ANCHOR, EXPONENT));
        assertEquals(0.0, KillExpHealthTerm.healthTerm(-1.0, 1000.0, ANCHOR, EXPONENT));
    }
}
