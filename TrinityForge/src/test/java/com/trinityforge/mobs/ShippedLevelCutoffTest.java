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
        // 2026-08-18 ユーザー指示「経験値は15〜30レベルの差の区間をかけて0になるようにしたい」。
        // アイテム側(20差で完全遮断)とは別の閾値から始まることが、この分離を入れた理由そのもの。
        ConfigurationSection under = underLevel();
        MobLevelCutoff cutoff = shippedCutoff();
        int expThreshold = under.getInt("exp-threshold", -1);
        assertEquals(15, expThreshold, "経験値の逓減は15レベル差から始まること");

        assertEquals(1.0, cutoff.expMultiplier(0, expThreshold - 1), 1e-9, "15差の手前は満額");
        assertEquals(1.0, cutoff.expMultiplier(0, expThreshold), 1e-9, "15差ちょうどはまだ満額");

        // アイテムの閾値(20)より手前の帯で既に減っていること。ここが 1.0 に戻ると exp-threshold が
        // 効いておらず、経験値もアイテムと同じ20差からしか絞られない(分離を入れた意味が消える)。
        int itemThreshold = under.getInt("item-threshold");
        for (int diff = expThreshold + 1; diff < itemThreshold; diff++) {
            assertTrue(cutoff.expMultiplier(0, diff) < 1.0,
                    "レベル差" + diff + "(アイテム閾値" + itemThreshold + "の手前)で経験値が減っていない");
        }

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
        // 経験値だけ手前から絞る、という分離が生きていること。ここが同値に戻ると
        // exp-threshold を足した意味が無くなり、20差まで満額のまま抜け穴が残る。
        ConfigurationSection under = underLevel();
        assertTrue(under.getInt("exp-threshold", -1) < under.getInt("item-threshold"),
                "経験値の閾値はアイテムの閾値より手前であること");
        assertTrue(shippedCutoff().expMultiplier(0, under.getInt("item-threshold")) < 1.0,
                "アイテムが止まるレベル差では経験値も既に減っていること");
    }
}
