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
    void shippedExpCurveFadesToZeroAcrossTheTwentyFiveToFiftyBand() {
        // 2026-08-19 W-148: 帯を 15〜30 差から【25〜50 差】へ広げた。
        //
        // 旧値はスキルレベルを基準に置かれていたが、この足きりが実際に比較するのは
        // 【戦闘レベル】(progression/combat-level.yml の pillar 写像)で、単一特化のプレイヤーでは
        // 最高スキルの約 2/3 にしかならない。つまり「スキル78 vs Lv80モブ」は
        // レベル差 2 ではなく 28 として判定され、旧値では 0.13 倍まで削られていた
        // (配備先DBの実データで、戦闘Lv53の人が約1000、戦闘Lv50以下の人がきっかり0)。
        // 25 は「純特化ぶんの構造的なズレ(最高スキルの 1/3)」を吸収する幅。
        ConfigurationSection under = underLevel();
        MobLevelCutoff cutoff = shippedCutoff();
        int expThreshold = under.getInt("exp-threshold", -1);
        assertEquals(25, expThreshold, "経験値の逓減は25レベル差から始まること");

        assertEquals(1.0, cutoff.expMultiplier(0, expThreshold - 1), 1e-9, "25差の手前は満額");
        assertEquals(1.0, cutoff.expMultiplier(0, expThreshold), 1e-9, "25差ちょうどはまだ満額");
        assertTrue(cutoff.expMultiplier(0, expThreshold + 1) < 1.0, "26差からは減り始めること");

        // 途中は単調に減り、両端の間で必ず中間値を通る(ステップ関数に戻っていないことの確認)。
        double at30 = cutoff.expMultiplier(0, 30);
        double at40 = cutoff.expMultiplier(0, 40);
        assertTrue(at30 > at40, "30差より40差のほうが少ないこと: " + at30 + " / " + at40);
        assertTrue(at40 > 0.0, "40差でまだ0になっていないこと(区間をかけて減る): " + at40);
        assertTrue(at30 < 1.0, "30差では既に減っていること: " + at30);

        // 50差で0。ここが「ハメ殺しても何も入らない」ライン。
        assertEquals(0.0, cutoff.expMultiplier(0, 50), 1e-9);
        assertEquals(0.0, cutoff.expMultiplier(0, 90), 1e-9);
    }

    @Test
    void carriedLowLevelPlayerStillGetsNeitherExpNorItems() {
        // 足きりを緩めても【ハメ狩り・お連れ様の抑制】という当初の狙いは残っていること。
        // 経験値の閾値(25)がアイテムの閾値(20)より後ろになったのは 2026-08-19 の意図的な変更で、
        // 旧テスト(shippedExpCutoffStartsEarlierThanTheItemCutoff)が固定していた
        // 「経験値のほうが手前から絞られる」という順序は【もう成り立たない】。
        // 順序そのものに意味があったのではなく「経験値にも効くこと」が狙いだったので、
        // ここでは順序ではなく“大差では両方止まる”という結果のほうを固定する。
        ConfigurationSection under = underLevel();
        MobLevelCutoff cutoff = shippedCutoff();
        int itemThreshold = under.getInt("item-threshold");

        assertTrue(cutoff.blocksItems(0, 50), "50差でTF追加ドロップは付かない");
        assertEquals(0.0, cutoff.expMultiplier(0, 50), 1e-9, "50差で経験値も0");
        assertTrue(cutoff.blocksItems(0, itemThreshold),
                "アイテム側の閾値(" + itemThreshold + ")は据え置きで、そこから完全遮断のままであること");
    }
}
