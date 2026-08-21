package com.trinityforge.mobs;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code combat/damage.yml} の {@code level-cutoff.under-level} が、
 * 「低レベルのままハメ殺し／デスルーラーで高レベルのモブを狩る」行為を実際に抑制する値になっているかを固定する
 * (2026-08-18 W-72 ユーザー要望「どちらかと言えば低レベルが羽目殺しやデスルーラーで高レベルのモブを狩ることを
 * 防ぎたい」)。
 *
 * <p><b>なぜ yml の値そのものをテストするか。</b> {@link MobLevelCutoff} のロジックがいくら正しくても、
 * 出荷設定の閾値が {@code -1}(無効)のままなら機能は1度も発動しない。2026-08-18 以前がまさにその状態で、
 * 「実装済みだが設定が無効」という理由で抜け穴が開いていた。値を戻す変更をしたらここが落ちる。
 *
 * <p>editor 側の同じ値の固定は {@code tools/config-editor/test/constants.test.js} にある。
 */
class ShippedLevelCutoffTest {

    private static ConfigurationSection underLevel() {
        try (InputStream in = ShippedLevelCutoffTest.class.getResourceAsStream("/combat/damage.yml")) {
            assertNotNull(in, "出荷 combat/damage.yml がクラスパスに無い");
            ConfigurationSection section = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getConfigurationSection("level-cutoff.under-level");
            assertNotNull(section, "level-cutoff.under-level ブロックが無い");
            return section;
        } catch (java.io.IOException e) {
            throw new AssertionError("出荷 combat/damage.yml を読めない", e);
        }
    }

    @Test
    void shippedUnderLevelCutoffIsEnabled() {
        // -1 は「この足きりは無効」。ここが -1 に戻ると機能ごと死ぬ(2026-08-18 以前の状態)。
        assertTrue(underLevel().getInt("item-threshold") >= 0,
                "under-level の閾値が無効値(-1)。低レベルでの高レベルモブ狩りが素通りになる");
    }

    private static MobLevelCutoff shippedCutoff() {
        ConfigurationSection under = underLevel();
        return new MobLevelCutoff(
                -1, 1.0, 1.0, under.getInt("item-threshold"), 0.0, 0.0, 0.0,
                under.getDouble("exp-rate"), under.getDouble("drop-rate"),
                under.getDouble("exp-decay-per-level"), under.getDouble("drop-decay-per-level"),
                under.getDouble("rate-floor"), under.getInt("exp-threshold", -1));
    }

    @Test
    void shippedItemCutoffStaysAtTheItemThreshold() {
        ConfigurationSection under = underLevel();
        MobLevelCutoff cutoff = shippedCutoff();
        int itemThreshold = under.getInt("item-threshold");

        // 閾値の1つ手前まではTF追加ドロップに無干渉 ── 正規の攻略(自分の帯のダンジョン)を巻き込まない。
        assertEquals(1.0, cutoff.dropChanceMultiplier(0, itemThreshold - 1), 1e-9);
        assertTrue(cutoff.blocksItems(0, itemThreshold), "閾値到達でTF追加ドロップは付かない");
    }

    @Test
    void shippedExpCurveFadesToZeroAcrossTheFifteenToThirtyBand() {
        // 2026-08-22 ユーザー指示で 25〜50 差から【15〜30 差】へ戻した。
        //
        // 25 は 2026-08-19 に「この足きりが比較するのは戦闘レベルで、単一特化のプレイヤーでは
        // 最高スキルの約 2/3 にしかならない」ぶんを吸収するための水増しとして置いた値だった。
        // 同じ日に職業EXPの判定基準を【そのEXPが入る職業のレベル】へ変えたので、
        // 水増しの理由そのものが消えた ── 据え置くと足きりが当初意図より大幅に緩くなる。
        // 15/0.067 は 2026-08-18 に最初に決めた形で、ようやく意図どおりの意味になった。
        // なお【バニラの経験値オーブとTF追加ドロップは今も戦闘レベル基準】で、
        // 変わったのは職業EXPが比較する数だけ(閾値・逓減は共通の1本)。
        ConfigurationSection under = underLevel();
        MobLevelCutoff cutoff = shippedCutoff();
        int expThreshold = under.getInt("exp-threshold", -1);
        assertEquals(15, expThreshold, "経験値の逓減は15レベル差から始まること");

        assertEquals(1.0, cutoff.expMultiplier(0, expThreshold - 1), 1e-9, "15差の手前は満額");
        assertEquals(1.0, cutoff.expMultiplier(0, expThreshold), 1e-9, "15差ちょうどはまだ満額");
        assertTrue(cutoff.expMultiplier(0, expThreshold + 1) < 1.0, "16差からは減り始めること");

        // 途中は単調に減り、両端の間で必ず中間値を通る(ステップ関数に戻っていないことの確認)。
        double at20 = cutoff.expMultiplier(0, 20);
        double at25 = cutoff.expMultiplier(0, 25);
        assertTrue(at20 > at25, "20差より25差のほうが少ないこと: " + at20 + " / " + at25);
        assertTrue(at25 > 0.0, "25差でまだ0になっていないこと(区間をかけて減る): " + at25);
        assertTrue(at20 < 1.0, "20差では既に減っていること: " + at20);

        // 30差で0。ここが「ハメ殺しても何も入らない」ライン。
        assertEquals(0.0, cutoff.expMultiplier(0, 30), 1e-9);
        assertEquals(0.0, cutoff.expMultiplier(0, 90), 1e-9);
    }

    @Test
    void shippedExpCutoffStartsEarlierThanTheItemCutoff() {
        // 2026-08-22 に経験値(15)がアイテム(20)より手前へ戻ったので、
        // 2026-08-18 の当初の順序が復活している。順序そのものが狙いではないが、
        // 「経験値だけ別の起点を持つ」という exp-threshold の存在理由が
        // 実際に使われていること(＝ -1 へ戻って1本化していないこと)をここで固定する。
        ConfigurationSection under = underLevel();
        int itemThreshold = under.getInt("item-threshold");
        int expThreshold = under.getInt("exp-threshold", -1);
        assertTrue(expThreshold >= 0, "exp-threshold が -1(item と1本化)へ戻っている");
        assertTrue(expThreshold < itemThreshold,
                "経験値の起点(" + expThreshold + ")はアイテムの起点(" + itemThreshold + ")より手前であること");

        MobLevelCutoff cutoff = shippedCutoff();
        assertTrue(cutoff.expMultiplier(0, itemThreshold) < 1.0,
                "アイテムが完全遮断される差では、経験値は既に減っていること");
        assertEquals(1.0, cutoff.dropChanceMultiplier(0, expThreshold), 1e-9,
                "経験値が絞られ始める差では、TF追加ドロップはまだ無干渉であること");
    }

    @Test
    void carriedLowLevelPlayerStillGetsNeitherExpNorItems() {
        // 閾値をどう動かしても【ハメ狩り・お連れ様の抑制】という当初の狙いは残っていること。
        // 個々の値ではなく“大差では両方止まる”という結果のほうを固定する。
        ConfigurationSection under = underLevel();
        MobLevelCutoff cutoff = shippedCutoff();
        int itemThreshold = under.getInt("item-threshold");

        assertTrue(cutoff.blocksItems(0, 50), "50差でTF追加ドロップは付かない");
        assertEquals(0.0, cutoff.expMultiplier(0, 50), 1e-9, "50差で経験値も0");
        assertTrue(cutoff.blocksItems(0, itemThreshold),
                "アイテム側の閾値(" + itemThreshold + ")は据え置きで、そこから完全遮断のままであること");
    }
}
