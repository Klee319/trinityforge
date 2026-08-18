package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MobLevelCutoff}: pure boundary-value logic for the 2026-07-27 「レベル差による足きり」機能.
 * No Bukkit involved — {@code MobOverrideDropListenerTest}/{@code MobOverrideExpListenerTest} cover the
 * listener wiring, {@code MobOverridesConfigTest} covers the YAML parse + 4段解決.
 */
class MobLevelCutoffTest {

    // --- NONE / isNone ---

    @Test
    void noneIsAlwaysInactiveAndNeverBlocks() {
        assertTrue(MobLevelCutoff.NONE.isNone());
        assertFalse(MobLevelCutoff.NONE.isOverLevelActive(999, 0));
        assertFalse(MobLevelCutoff.NONE.isUnderLevelActive(0, 999));
        assertFalse(MobLevelCutoff.NONE.blocksItems(999, 0));
        assertEquals(1.0, MobLevelCutoff.NONE.dropChanceMultiplier(999, 0));
        assertEquals(1.0, MobLevelCutoff.NONE.expMultiplier(999, 0));
    }

    @Test
    void anyConfiguredFieldMakesIsNoneFalse() {
        assertFalse(new MobLevelCutoff(10, null, null, null).isNone());
        assertFalse(new MobLevelCutoff(null, 0.5, null, null).isNone());
        assertFalse(new MobLevelCutoff(null, null, 0.5, null).isNone());
        assertFalse(new MobLevelCutoff(null, null, null, 10).isNone());
    }

    // --- over-level threshold boundary ---

    @Test
    void overLevelActiveExactlyAtThreshold() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, -1.0, null, null);
        // diff = playerLevel - mobLevel = 10 - 0 = 10, exactly at threshold -> active (>=, not >).
        assertTrue(cutoff.isOverLevelActive(10, 0));
    }

    @Test
    void overLevelInactiveOneBelowThreshold() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, -1.0, null, null);
        assertFalse(cutoff.isOverLevelActive(9, 0));
    }

    @Test
    void overLevelThresholdNullMeansAlwaysInactive() {
        MobLevelCutoff cutoff = new MobLevelCutoff(null, -1.0, -1.0, null);
        assertFalse(cutoff.isOverLevelActive(999, 0));
    }

    @Test
    void overLevelThresholdNegativeMeansAlwaysInactive() {
        // Spec: "未設定/負値 = 無効" — a negative threshold is a valid way to author "disabled".
        MobLevelCutoff cutoff = new MobLevelCutoff(-1, -1.0, -1.0, null);
        assertFalse(cutoff.isOverLevelActive(999, 0));
    }

    // --- under-level threshold boundary ---

    @Test
    void underLevelActiveExactlyAtThreshold() {
        MobLevelCutoff cutoff = new MobLevelCutoff(null, null, null, 20);
        // mobLevel - playerLevel = 20 - 0 = 20, exactly at threshold -> active.
        assertTrue(cutoff.isUnderLevelActive(0, 20));
    }

    @Test
    void underLevelInactiveOneBelowThreshold() {
        MobLevelCutoff cutoff = new MobLevelCutoff(null, null, null, 20);
        assertFalse(cutoff.isUnderLevelActive(0, 19));
    }

    @Test
    void underLevelThresholdNullOrNegativeMeansAlwaysInactive() {
        assertFalse(new MobLevelCutoff(null, null, null, null).isUnderLevelActive(0, 999));
        assertFalse(new MobLevelCutoff(null, null, null, -1).isUnderLevelActive(0, 999));
    }

    // --- blocksItems ---

    @Test
    void underLevelActiveBlocksItemsRegardlessOfOverLevelFields() {
        MobLevelCutoff cutoff = new MobLevelCutoff(null, null, null, 20);
        assertTrue(cutoff.blocksItems(0, 20));
    }

    @Test
    void overLevelActiveWithDropRateMinusOneBlocksItems() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, null, -1.0, null);
        assertTrue(cutoff.blocksItems(10, 0));
    }

    @Test
    void overLevelActiveWithNonMinusOneDropRateDoesNotBlockOutright() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, null, 0.5, null);
        assertFalse(cutoff.blocksItems(10, 0));
    }

    @Test
    void overLevelActiveWithNullDropRateDoesNotBlock() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, null, null, null);
        assertFalse(cutoff.blocksItems(10, 0));
    }

    @Test
    void neitherCutoffActiveDoesNotBlock() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, -1.0, -1.0, 20);
        assertFalse(cutoff.blocksItems(5, 0)); // diff=5 < 10, mobLevel-player=-5 < 20
    }

    // --- dropChanceMultiplier ---

    @Test
    void dropChanceMultiplierIsZeroWhenBlocked() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, null, -1.0, null);
        assertEquals(0.0, cutoff.dropChanceMultiplier(10, 0));
    }

    @Test
    void dropChanceMultiplierAppliesConfiguredRateWhenActiveAndNotBlocked() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, null, 0.25, null);
        assertEquals(0.25, cutoff.dropChanceMultiplier(10, 0));
    }

    @Test
    void dropChanceMultiplierClampsAboveOne() {
        // Parse-time validation should already reject >1, but the pure method itself defends anyway.
        MobLevelCutoff cutoff = new MobLevelCutoff(10, null, 2.0, null);
        assertEquals(1.0, cutoff.dropChanceMultiplier(10, 0));
    }

    @Test
    void dropChanceMultiplierIsOneWhenOverLevelNotActive() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, null, 0.25, null);
        assertEquals(1.0, cutoff.dropChanceMultiplier(5, 0));
    }

    @Test
    void dropChanceMultiplierIsOneWhenRateUnset() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, null, null, null);
        assertEquals(1.0, cutoff.dropChanceMultiplier(10, 0));
    }

    // --- expMultiplier ---

    @Test
    void expMultiplierIsZeroWhenRateIsMinusOneAndActive() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, -1.0, null, null);
        assertEquals(0.0, cutoff.expMultiplier(10, 0));
    }

    @Test
    void expMultiplierAppliesConfiguredRateWhenActive() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, 0.25, null, null);
        assertEquals(0.25, cutoff.expMultiplier(10, 0));
    }

    @Test
    void expMultiplierIsOneWhenNotActive() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, 0.25, null, null);
        assertEquals(1.0, cutoff.expMultiplier(5, 0));
    }

    @Test
    void expMultiplierIsOneWhenRateUnsetEvenIfActive() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, null, -1.0, null);
        assertEquals(1.0, cutoff.expMultiplier(10, 0));
    }

    @Test
    void underLevelLeavesExpAloneWhenItsRateIsUnset() {
        // 2026-08-18(W-72)で under-level も経験値へ効くようにしたが、under側の exp-rate が
        // 未設定(=旧コンストラクタの既定)なら従来どおり経験値には触れない。
        MobLevelCutoff cutoff = new MobLevelCutoff(null, null, null, 1);
        assertEquals(1.0, cutoff.expMultiplier(0, 50));
    }

    @Test
    void expMultiplierClampsAboveOne() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, 2.0, null, null);
        assertEquals(1.0, cutoff.expMultiplier(10, 0));
    }

    // --- 2026-08-18 (W-60) 線形傾斜: decay-per-level / rate-floor ---

    @Test
    void fourArgConstructorDefaultsDecayFieldsToZeroForBackwardCompatibility() {
        // 4引数コンストラクタ(既存呼び出し元)は decay系3フィールドを0.0にする。
        MobLevelCutoff cutoff = new MobLevelCutoff(10, 1.0, 1.0, null);
        assertEquals(0.0, cutoff.overLevelExpDecayPerLevel());
        assertEquals(0.0, cutoff.overLevelDropDecayPerLevel());
        assertEquals(0.0, cutoff.overLevelRateFloor());
    }

    @Test
    void zeroDecayReproducesLegacyStepFunctionRegardlessOfExcess() {
        // decay=0 なら超過が5でも50でも基準rateのまま(後方互換の直接証拠)。
        MobLevelCutoff cutoff = new MobLevelCutoff(10, 1.0, 0.5, null, 0.0, 0.0, 0.0);
        assertEquals(1.0, cutoff.expMultiplier(15, 0), "超過5でも減衰0なら基準値のまま");
        assertEquals(1.0, cutoff.expMultiplier(60, 0), "超過50でも減衰0なら基準値のまま");
        assertEquals(0.5, cutoff.dropChanceMultiplier(15, 0), "超過5でも減衰0なら基準値のまま");
        assertEquals(0.5, cutoff.dropChanceMultiplier(60, 0), "超過50でも減衰0なら基準値のまま");
    }

    @Test
    void expMultiplierDecaysLinearlyPastThreshold() {
        // RED実証シナリオ: threshold=10, exp-rate=1.0, exp-decay-per-level=0.05, rate-floor既定0。
        MobLevelCutoff cutoff = new MobLevelCutoff(10, 1.0, null, null, 0.05, 0.0, 0.0);
        // excess=5 -> 1.0 - 0.05*5 = 0.75
        assertEquals(0.75, cutoff.expMultiplier(15, 0), 1e-9);
        // excess=50 -> 1.0 - 0.05*50 = -1.5 -> floor(0.0)にクランプ
        assertEquals(0.0, cutoff.expMultiplier(60, 0), 1e-9);
        // excess=0(閾値ちょうど) -> 減衰なし、基準値のまま
        assertEquals(1.0, cutoff.expMultiplier(10, 0), 1e-9);
    }

    @Test
    void expMultiplierRespectsCustomRateFloor() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, 1.0, null, null, 0.05, 0.0, 0.3);
        // excess=50 -> 1.0 - 2.5 = -1.5 -> floor 0.3 にクランプ(0.0ではない)
        assertEquals(0.3, cutoff.expMultiplier(60, 0), 1e-9);
    }

    @Test
    void dropChanceMultiplierDecaysLinearlyPastThreshold() {
        MobLevelCutoff cutoff = new MobLevelCutoff(10, null, 1.0, null, 0.0, 0.1, 0.0);
        // excess=3 -> 1.0 - 0.1*3 = 0.7
        assertEquals(0.7, cutoff.dropChanceMultiplier(13, 0), 1e-9);
    }

    @Test
    void minusOneShortCircuitsBeforeDecayCalculation() {
        // rate=-1 は減衰計算を経由せず常に完全遮断/0を返す(超過が0でも巨大でも変わらない)。
        MobLevelCutoff exp = new MobLevelCutoff(10, -1.0, null, null, 0.05, 0.0, 0.5);
        assertEquals(0.0, exp.expMultiplier(10, 0));
        assertEquals(0.0, exp.expMultiplier(60, 0));

        MobLevelCutoff drop = new MobLevelCutoff(10, null, -1.0, null, 0.0, 0.05, 0.5);
        assertTrue(drop.blocksItems(10, 0));
        assertEquals(0.0, drop.dropChanceMultiplier(60, 0));
    }

    @Test
    void expMultiplierNeverGoesNegativeEvenWithoutExplicitFloor() {
        // floor未設定(null)は0.0扱い ── 減衰しすぎても回復扱いの負値にはならない。
        MobLevelCutoff cutoff = new MobLevelCutoff(10, 0.5, null, null, 1.0, 0.0, null);
        assertEquals(0.0, cutoff.expMultiplier(200, 0));
    }

    // --- 2026-08-18 (W-72) under-level を over-level と完全対称にした ---

    @Test
    void sevenArgConstructorKeepsTheLegacyUnderLevelBehaviour() {
        // 対称化前の under-level は「経験値には触れない / 発動したら追加ドロップを一切付けない」。
        // 7引数コンストラクタ(既存呼び出し元・既存テスト)はこの意味を保つ必要がある。
        MobLevelCutoff cutoff = new MobLevelCutoff(null, null, null, 20, 0.0, 0.0, 0.0);
        assertNull(cutoff.underLevelExpRate());
        assertEquals(-1.0, cutoff.underLevelDropRate().doubleValue());
        assertEquals(1.0, cutoff.expMultiplier(0, 20), "under側 exp-rate 未設定なら経験値は素通り");
        assertTrue(cutoff.blocksItems(0, 20), "under側 drop-rate=-1 なので追加ドロップは完全遮断");
    }

    @Test
    void underLevelExpRateMinusOneZeroesExp() {
        // ここが対称化の本命 ── 低レベルのままハメ殺しで高レベルモブを倒しても経験値が入らない設定。
        MobLevelCutoff cutoff = new MobLevelCutoff(null, null, null, 20, 0.0, 0.0, 0.0,
                -1.0, -1.0, 0.0, 0.0, 0.0);
        assertEquals(0.0, cutoff.expMultiplier(0, 20));
        assertEquals(1.0, cutoff.expMultiplier(0, 19), "閾値未満は無干渉");
    }

    @Test
    void underLevelExpDecaysLinearlyPastThresholdLikeOverLevel() {
        // 出荷値そのもの: item-threshold=20 / exp-rate=1.0 / exp-decay-per-level=0.1 / rate-floor=0。
        MobLevelCutoff cutoff = new MobLevelCutoff(-1, 1.0, 1.0, 20, 0.0, 0.0, 0.0,
                1.0, -1.0, 0.1, 0.0, 0.0);
        assertEquals(1.0, cutoff.expMultiplier(0, 20), 1e-9, "閾値ちょうど(20差)は減衰なし");
        assertEquals(0.5, cutoff.expMultiplier(0, 25), 1e-9, "25差 = 超過5 -> 1.0 - 0.1*5");
        assertEquals(0.0, cutoff.expMultiplier(0, 30), 1e-9, "30差 = 超過10 -> 経験値0");
        assertEquals(0.0, cutoff.expMultiplier(0, 90), 1e-9, "それ以上は下限0でクランプ");
        assertEquals(1.0, cutoff.expMultiplier(0, 19), 1e-9, "19差はまだ無干渉");
    }

    @Test
    void underLevelIsExactlySymmetricWithOverLevel() {
        // 同じ数値をそれぞれの向きに置き、レベルを鏡写しにすると同じ倍率になる(対称性そのものの証明)。
        MobLevelCutoff over = new MobLevelCutoff(15, 0.8, 0.6, null, 0.05, 0.02, 0.1);
        MobLevelCutoff under = new MobLevelCutoff(null, null, null, 15, 0.0, 0.0, 0.0,
                0.8, 0.6, 0.05, 0.02, 0.1);
        for (int excess = 0; excess <= 30; excess++) {
            assertEquals(over.expMultiplier(15 + excess, 0), under.expMultiplier(0, 15 + excess), 1e-9,
                    "超過" + excess + "レベルでの経験値倍率が非対称");
            assertEquals(over.dropChanceMultiplier(15 + excess, 0), under.dropChanceMultiplier(0, 15 + excess), 1e-9,
                    "超過" + excess + "レベルでのドロップ倍率が非対称");
        }
    }

    @Test
    void underLevelDropRateOtherThanMinusOneDecaysInsteadOfBlockingOutright() {
        MobLevelCutoff cutoff = new MobLevelCutoff(null, null, null, 20, 0.0, 0.0, 0.0,
                null, 1.0, 0.0, 0.1, 0.0);
        assertFalse(cutoff.blocksItems(0, 20), "-1 以外なら完全遮断にはならない");
        assertEquals(1.0, cutoff.dropChanceMultiplier(0, 20), 1e-9);
        assertEquals(0.7, cutoff.dropChanceMultiplier(0, 23), 1e-9, "超過3 -> 1.0 - 0.1*3");
    }

    @Test
    void bothSidesActiveTakesTheStricterMultiplier() {
        // 両方が同時に発動するのは閾値が両方0でプレイヤーとモブが同レベルのときだけ。
        // そのとき厳しいほう(小さいほう)を採る。
        MobLevelCutoff cutoff = new MobLevelCutoff(0, 0.8, 0.8, 0, 0.0, 0.0, 0.0,
                0.3, 0.3, 0.0, 0.0, 0.0);
        assertTrue(cutoff.isOverLevelActive(10, 10) && cutoff.isUnderLevelActive(10, 10));
        assertEquals(0.3, cutoff.expMultiplier(10, 10), 1e-9);
        assertEquals(0.3, cutoff.dropChanceMultiplier(10, 10), 1e-9);
    }

    @Test
    void minusOneOnEitherSideWinsOverTheOtherSidesRate() {
        MobLevelCutoff underBlocks = new MobLevelCutoff(0, 1.0, 1.0, 0, 0.0, 0.0, 0.0,
                -1.0, -1.0, 0.0, 0.0, 0.0);
        assertEquals(0.0, underBlocks.expMultiplier(10, 10));
        assertTrue(underBlocks.blocksItems(10, 10));

        MobLevelCutoff overBlocks = new MobLevelCutoff(0, -1.0, -1.0, 0, 0.0, 0.0, 0.0,
                1.0, 1.0, 0.0, 0.0, 0.0);
        assertEquals(0.0, overBlocks.expMultiplier(10, 10));
        assertTrue(overBlocks.blocksItems(10, 10));
    }
}
