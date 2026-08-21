package com.trinityforge.listeners;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「成長効率 +X%」が<b>本当に成長速度 ×(1 + X) を意味する</b>ことを、作物と動物の<b>両方</b>で固定する
 * （2026-08-21 ユーザー質問「成長効率25%って1.25倍の速度で成長する認識でいい？」→ 当時は<b>いいえ</b>だった）。
 *
 * <p><b>着手前の状態</b>: 同じ「成長効率」という名前・同じ percent 表示なのに、実体が2つとも
 * 倍率ではなく、しかも互いに違っていた。
 * <ul>
 *   <li><b>作物</b>: 40tick(2秒)ごとに {@code min(1, bonus)} の確率で {@code age+1} する
 *       <b>独立したポーリング</b>。バニラの成長速度とは無関係で、+25% は 1段階あたり平均8秒
 *       ＝小麦(7段階)が約56秒 ── バニラが最良条件でも1段階2分前後なので<b>体感は十数倍</b>だった。
 *       おまけに {@code min(1.0, ...)} のせいで<b>100% を超えても速くならなかった</b>。</li>
 *   <li><b>動物</b>: {@code 残り × (1 - bonus)}。+25% は「残り時間 −25%」＝<b>速度 1.333 倍</b>で、
 *       1.25 倍ではなかった。こちらは 90% 短縮で頭打ち。</li>
 * </ul>
 *
 * <p>どちらも「バニラ速度 ×(1 + bonus)」へ揃えた。数値そのもの（skilltree/farming.yml の buffs）は
 * ユーザーが調整する前提なので、ここで固定するのは<b>意味だけ</b>。
 */
class GrowthBonusIsARealMultiplierTest {

    // ── 作物 ────────────────────────────────────────────────────────────────

    /**
     * 1回のバニラ成長で進む段階の期待値が {@code 1 + bonus} であること。
     * これが「成長速度 ×(1 + bonus)」の定義そのもの。
     */
    private static double expectedStagesPerVanillaGrowth(double bonus, int samples) {
        double total = 0.0;
        for (int i = 0; i < samples; i++) {
            // [0,1) を等間隔に走査する（乱数ではなく決定的に期待値を出す）。
            double roll = (i + 0.5) / samples;
            total += 1 + PlantedCropGrowthListener.extraStages(bonus, roll);
        }
        return total / samples;
    }

    @Test
    @DisplayName("作物: +25% はバニラ成長 1 回あたり平均 1.25 段階 = 1.25 倍（報告そのもの）")
    void aQuarterBonusMakesCropsGrowExactlyOnePointTwoFiveTimesFaster() {
        assertEquals(1.25, expectedStagesPerVanillaGrowth(0.25, 10_000), 1e-3);
    }

    @Test
    @DisplayName("作物: 倍率に上限が無い（旧実装は 100% で頭打ちだった）")
    void theCropMultiplierHasNoCeiling() {
        assertEquals(2.0, expectedStagesPerVanillaGrowth(1.0, 10_000), 1e-3);
        assertEquals(3.0, expectedStagesPerVanillaGrowth(2.0, 10_000), 1e-3);
        assertEquals(4.5, expectedStagesPerVanillaGrowth(3.5, 10_000), 1e-3);

        // 旧実装は min(1.0, bonus) だったので、2.0 でも 1.0 と同じ速さにしかならなかった。
        assertTrue(expectedStagesPerVanillaGrowth(2.0, 10_000)
                        > expectedStagesPerVanillaGrowth(1.0, 10_000),
                "100% を超えたぶんが効いていない（頭打ちが戻っている）");
    }

    @Test
    @DisplayName("作物: 整数部は必ず足し、小数部だけを抽選する")
    void theWholePartIsAlwaysAddedAndOnlyTheFractionIsRolled() {
        // bonus 2.25: どんな roll でも最低 +2、25% の確率で +3。
        assertEquals(3, PlantedCropGrowthListener.extraStages(2.25, 0.10));
        assertEquals(2, PlantedCropGrowthListener.extraStages(2.25, 0.30));
        assertEquals(2, PlantedCropGrowthListener.extraStages(2.25, 0.99));
    }

    @Test
    @DisplayName("作物: ボーナス 0 / 負 / NaN では 1 段階のまま（バニラどおり）")
    void withoutABonusVanillaGrowthIsUntouched() {
        assertEquals(0, PlantedCropGrowthListener.extraStages(0.0, 0.0));
        assertEquals(0, PlantedCropGrowthListener.extraStages(-1.0, 0.0));
        assertEquals(0, PlantedCropGrowthListener.extraStages(Double.NaN, 0.0));
        assertEquals(0, PlantedCropGrowthListener.extraStages(Double.POSITIVE_INFINITY, 0.0));
    }

    // ── 動物 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("動物: +25% は残り時間 0.8 倍 = 速度 1.25 倍（旧実装は 0.75 倍 = 1.333 倍だった）")
    void aQuarterBonusMakesBabiesGrowExactlyOnePointTwoFiveTimesFaster() {
        double remaining = BreedingBonusListener.remainingFraction(0.25);

        assertEquals(0.8, remaining, 1e-9);
        assertEquals(1.25, 1.0 / remaining, 1e-9, "残り時間の逆数が成長速度の倍率になる");
        assertTrue(remaining > 0.75,
                "旧実装の『残り×(1-bonus)』へ戻っている（0.75 は 1.333 倍で 1.25 倍ではない）");
    }

    @Test
    @DisplayName("動物: 作物と同じ倍率になる（同じ名前のステが同じ意味であること）")
    void cropsAndAnimalsAgreeOnWhatTheBonusMeans() {
        for (double bonus : new double[] {0.25, 0.5, 1.0, 2.0}) {
            double crops = expectedStagesPerVanillaGrowth(bonus, 10_000);
            double animals = 1.0 / BreedingBonusListener.remainingFraction(bonus);
            assertEquals(crops, animals, 1e-3,
                    "bonus " + bonus + " で作物と動物の倍率が食い違っている: 作物 " + crops + " / 動物 " + animals);
        }
    }

    @Test
    @DisplayName("動物: 残り時間の下限は元の 10%（= 速度 x10 が上限。旧「90%短縮」と同じ天井）")
    void theBabyGrowthSpeedUpIsCappedAtTenTimes() {
        assertEquals(0.1, BreedingBonusListener.remainingFraction(9.0), 1e-9);
        assertEquals(0.1, BreedingBonusListener.remainingFraction(100.0), 1e-9);
    }

    @Test
    @DisplayName("動物: ボーナス 0 / 負 / NaN では残り時間を変えない")
    void withoutABonusTheBabyTimerIsUntouched() {
        assertEquals(1.0, BreedingBonusListener.remainingFraction(0.0), 1e-9);
        assertEquals(1.0, BreedingBonusListener.remainingFraction(-1.0), 1e-9);
        assertEquals(1.0, BreedingBonusListener.remainingFraction(Double.NaN), 1e-9);
    }
}
