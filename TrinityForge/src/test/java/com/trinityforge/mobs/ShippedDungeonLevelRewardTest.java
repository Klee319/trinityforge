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
 * 出荷 {@code combat/damage.yml} の {@code dungeon-level-reward} が、EMダイナミックダンジョンで
 * 「高いレベルを選ぶ理由」を実際に作る値になっているかを固定する(2026-08-18 W-80)。
 *
 * <p><b>なぜ yml の値そのものをテストするか。</b> {@link DungeonLevelReward} のロジックが正しくても、
 * 出荷設定が {@code enabled: false} や {@code cap: 0} のままなら機能は1度も発動しない。
 * {@code level-cutoff} で同じ罠(実装済みだが閾値 -1 で無効)を踏んでいるので、同じ形で固定する。
 */
class ShippedDungeonLevelRewardTest {

    private static ConfigurationSection section() {
        try (InputStream in = ShippedDungeonLevelRewardTest.class.getResourceAsStream("/combat/damage.yml")) {
            assertNotNull(in, "出荷 combat/damage.yml がクラスパスに無い");
            ConfigurationSection loaded = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getConfigurationSection("dungeon-level-reward");
            assertNotNull(loaded, "dungeon-level-reward ブロックが無い");
            return loaded;
        } catch (java.io.IOException e) {
            throw new AssertionError("出荷 combat/damage.yml を読めない", e);
        }
    }

    private static DungeonLevelReward shipped() {
        ConfigurationSection s = section();
        return new DungeonLevelReward(
                s.getBoolean("enabled"),
                s.getInt("base-level"),
                s.getDouble("drop-bonus-per-level"),
                s.getDouble("drop-bonus-cap"),
                s.getDouble("exp-bonus-per-level"),
                s.getDouble("exp-bonus-cap"));
    }

    @Test
    void shippedRewardIsEnabledAndActuallyDoesSomething() {
        assertTrue(section().getBoolean("enabled"), "出荷設定で無効になっていると1度も発動しない");
        assertTrue(!shipped().isNone(), "enabled でも per-level/cap が0だと実質無効");
    }

    @Test
    void lowLevelDungeonsAreLeftCompletelyAlone() {
        DungeonLevelReward reward = shipped();
        int base = section().getInt("base-level");
        assertEquals(1.0, reward.dropMultiplierAt(base), 1e-9, "base-level 以下は等倍(初挑戦帯を巻き込まない)");
        assertEquals(1.0, reward.expMultiplierAt(base), 1e-9);
    }

    @Test
    void highLevelDungeonsPayMeaningfullyMore() {
        // 「高いレベルを選ぶ理由」が実際にある水準か。ここが 1.0 近辺に戻ると狙いごと死ぬ。
        DungeonLevelReward reward = shipped();
        assertTrue(reward.dropMultiplierAt(60) >= 1.5,
                "レベル60のダンジョンでTF追加ドロップ確率が1.5倍未満だと選ぶ動機にならない: "
                        + reward.dropMultiplierAt(60));
        assertTrue(reward.expMultiplierAt(60) > 1.0, "撃破EXPにも上乗せが乗ること");
    }

    @Test
    void bonusIsCappedSoItCannotRunAway() {
        DungeonLevelReward reward = shipped();
        double atCap = reward.dropMultiplierAt(Integer.MAX_VALUE);
        assertTrue(atCap <= 3.0, "ドロップ確率の上乗せが3倍を超えると足きり側の抑制が意味を失う: " + atCap);
        assertEquals(atCap, reward.dropMultiplierAt(10_000), 1e-9);
    }
}
