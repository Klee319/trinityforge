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

    /** 出荷 yml の {@code under-level} ブロックをそのまま組み立てたもの(上乗せの2キーも含む)。 */
    private static MobLevelCutoff shipped() {
        ConfigurationSection under = underLevel();
        return new MobLevelCutoff(
                -1, 1.0, 1.0, under.getInt("item-threshold"), 0.0, 0.0, 0.0,
                under.getDouble("exp-rate"), under.getDouble("drop-rate"),
                under.getDouble("exp-decay-per-level"), under.getDouble("drop-decay-per-level"),
                under.getDouble("rate-floor"),
                under.getDouble("bonus-per-level"), under.getDouble("bonus-cap"));
    }

    @Test
    void shippedUnderLevelCurveZeroesExpAtATenLevelExcess() {
        MobLevelCutoff cutoff = shipped();
        int threshold = underLevel().getInt("item-threshold");

        // 閾値ちょうどでTF追加ドロップは止まり、経験値はここから逓減が始まる。
        assertTrue(cutoff.blocksItems(0, threshold), "閾値到達でTF追加ドロップは付かない");
        assertEquals(1.0, cutoff.expMultiplier(0, threshold), 1e-9, "閾値ちょうどでは経験値はまだ満額");

        // 閾値からさらに10レベル開くと経験値0。ここが「ハメ殺しても何も入らない」ラインになる。
        assertEquals(0.0, cutoff.expMultiplier(0, threshold + 10), 1e-9);
        assertEquals(0.0, cutoff.expMultiplier(0, threshold + 60), 1e-9);
    }

    /**
     * 出荷設定で「少し格上のモブを倒すと報酬が増える」ことを固定する(2026-08-18 W-80)。
     *
     * <p>EM のダイナミックダンジョンは入場時に「自分の戦闘レベル −5 / ±0 / +5」からレベルを選べるが、
     * 選んだレベルはボスの強さにしか効かず<b>報酬側に一切効いていなかった</b>ので、高いレベルを選ぶ理由が
     * 構造的に存在しなかった(ユーザー報告「低レベルで挑んだ方が簡単に勝ててしまう／高レベルで挑む理由を作る
     * 必要がありそう」)。ここの値が 0 に戻ると、その理由がまた消える。
     */
    @Test
    void shippedBonusRewardsFightingAboveYourLevel() {
        ConfigurationSection under = underLevel();
        assertTrue(under.getDouble("bonus-per-level") > 0.0 && under.getDouble("bonus-cap") > 0.0,
                "格上ボーナスが無効値。ダイナミックダンジョンで高いレベルを選ぶ理由が無くなる");

        MobLevelCutoff cutoff = shipped();
        // ダンジョンで「+5」を選んだときに実際に効く倍率。経験値もTF追加ドロップ確率も同じだけ増える。
        double plus5 = cutoff.expMultiplier(50, 55);
        assertTrue(plus5 > 1.0, "+5レベルを選んでも報酬が増えないなら、高レベルを選ぶ理由が無い");
        assertEquals(plus5, cutoff.dropChanceMultiplier(50, 55), 1e-9, "経験値とドロップで倍率が食い違う");
        // 「−5」を選んだ側(自分のほうが高レベル)には上乗せが乗らない = 低レベル選択が有利にならない。
        assertEquals(1.0, cutoff.expMultiplier(50, 45), 1e-9);
    }

    /**
     * 上乗せがハメ殺し／デスルーラー対策(W-72)に穴を空けていないこと。
     * 足きりの閾値に届いた差では上乗せは一切乗らず、遮断と逓減がそのまま効く。
     */
    @Test
    void shippedBonusNeverReopensTheAntiCheeseCutoff() {
        MobLevelCutoff cutoff = shipped();
        int threshold = underLevel().getInt("item-threshold");
        assertEquals(0.0, cutoff.dropChanceMultiplier(0, threshold), 1e-9,
                "閾値到達時のTF追加ドロップ遮断が上乗せで復活している");
        assertEquals(0.0, cutoff.expMultiplier(0, threshold + 10), 1e-9,
                "閾値+10 の経験値0 が上乗せで復活している");
    }
}
