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
 * 「高いレベル・高い難易度を選ぶ理由」を実際に作る値になっているかを固定する(2026-08-18 W-80)。
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
                s.getInt("pivot-level"),
                s.getInt("step"),
                s.getDouble("drop-bonus-per-step"),
                s.getDouble("drop-bonus-cap"),
                s.getDouble("drop-penalty-cap"),
                s.getDouble("exp-bonus-per-step"),
                s.getDouble("exp-bonus-cap"),
                s.getDouble("exp-penalty-cap"));
    }

    @Test
    void shippedRewardIsEnabledAndActuallyDoesSomething() {
        assertTrue(section().getBoolean("enabled"), "出荷設定で無効になっていると1度も発動しない");
        assertTrue(!shipped().isNone(), "enabled でも per-step/cap が0だと実質無効");
    }

    @Test
    void shippedCurveIsNeutralAtThePivotAndSignedOnBothSides() {
        DungeonLevelReward reward = shipped();
        int pivot = section().getInt("pivot-level");
        assertEquals(1.0, reward.dropMultiplierAt(pivot), 1e-9, "pivot ちょうどは規定値");
        assertEquals(1.0, reward.expMultiplierAt(pivot), 1e-9);
        assertTrue(reward.dropMultiplierAt(pivot - 10) < 1.0,
                "低いレベルのダンジョンは規定値より少なくなること(2026-08-18 ユーザー指示)");
        assertTrue(reward.expMultiplierAt(pivot - 10) < 1.0);
        assertTrue(reward.dropMultiplierAt(pivot + 10) > 1.0, "高いレベルのダンジョンは多くなること");
        assertTrue(reward.expMultiplierAt(pivot + 10) > 1.0);
    }

    @Test
    void shippedStepMakesEachDifficultyTierWorthPicking() {
        // フォークが normal/hard/mythic でモブレベルを -5/±0/+5 動かすので、5レベル差で段が変わること。
        // ここが変わらないと3つの難易度を選び分ける理由が消える。
        assertEquals(5, section().getInt("step"), "難易度の相対 levelSync ±5 と刻み幅を合わせる");
        DungeonLevelReward reward = shipped();
        int pivot = section().getInt("pivot-level");
        assertTrue(reward.dropMultiplierAt(pivot + 5) > reward.dropMultiplierAt(pivot),
                "1難易度ぶん(5レベル)でドロップの段が上がること");
        assertTrue(reward.expMultiplierAt(pivot + 5) > reward.expMultiplierAt(pivot),
                "1難易度ぶん(5レベル)でEXPの段が上がること");
    }

    @Test
    void shippedCapsAreLowerThanTheOneSidedDesignTheyReplaced() {
        // 2026-08-18 ユーザー指示「打ち止めの倍率をもっと下げる」。差し替え前は 2.5倍 / 1.75倍 だった。
        DungeonLevelReward reward = shipped();
        double topDrop = reward.dropMultiplierAt(Integer.MAX_VALUE);
        double topExp = reward.expMultiplierAt(Integer.MAX_VALUE);
        assertTrue(topDrop <= 2.0, "ドロップ確率の頭打ちが2倍を超えると足きり側の抑制が意味を失う: " + topDrop);
        assertTrue(topExp <= 1.5, "EXPの頭打ちが1.5倍を超えると下げた意味が無い: " + topExp);
        assertEquals(topDrop, reward.dropMultiplierAt(10_000), 1e-9, "頭打ちより上は平ら");
    }

    @Test
    void shippedPenaltyNeverWipesOutTheReward() {
        DungeonLevelReward reward = shipped();
        assertTrue(reward.dropMultiplierAt(Integer.MIN_VALUE) >= 0.5,
                "低レベル帯の減少が半分を下回ると初挑戦の帯が回らなくなる");
        assertTrue(reward.expMultiplierAt(Integer.MIN_VALUE) >= 0.5);
    }
}
