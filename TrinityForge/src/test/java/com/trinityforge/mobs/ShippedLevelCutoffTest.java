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

    @Test
    void shippedUnderLevelCurveZeroesExpAtATenLevelExcess() {
        ConfigurationSection under = underLevel();
        MobLevelCutoff cutoff = new MobLevelCutoff(
                -1, 1.0, 1.0, under.getInt("item-threshold"), 0.0, 0.0, 0.0,
                under.getDouble("exp-rate"), under.getDouble("drop-rate"),
                under.getDouble("exp-decay-per-level"), under.getDouble("drop-decay-per-level"),
                under.getDouble("rate-floor"));
        int threshold = under.getInt("item-threshold");

        // 閾値の1つ手前までは完全に無干渉 ── 正規の攻略(自分の帯のダンジョン)を巻き込まない。
        assertEquals(1.0, cutoff.expMultiplier(0, threshold - 1), 1e-9);
        assertEquals(1.0, cutoff.dropChanceMultiplier(0, threshold - 1), 1e-9);

        // 閾値ちょうどでTF追加ドロップは止まり、経験値はここから逓減が始まる。
        assertTrue(cutoff.blocksItems(0, threshold), "閾値到達でTF追加ドロップは付かない");
        assertEquals(1.0, cutoff.expMultiplier(0, threshold), 1e-9, "閾値ちょうどでは経験値はまだ満額");

        // 閾値からさらに10レベル開くと経験値0。ここが「ハメ殺しても何も入らない」ラインになる。
        assertEquals(0.0, cutoff.expMultiplier(0, threshold + 10), 1e-9);
        assertEquals(0.0, cutoff.expMultiplier(0, threshold + 60), 1e-9);
    }
}
