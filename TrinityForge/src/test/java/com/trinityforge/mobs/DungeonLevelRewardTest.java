package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DungeonLevelReward}: 2026-08-18 (W-80) 「ダンジョンの挑戦レベルに応じた報酬の上乗せ」の
 * 純粋ロジック。Bukkit非依存。
 *
 * <p>ダンジョンワールドかどうかの判定は {@code KillRewardAdjuster} が持つので、ここでは扱わない
 * (そちらは {@code KillRewardAdjusterDungeonBonusTest} が固定する)。
 */
class DungeonLevelRewardTest {

    private static DungeonLevelReward shipped() {
        // 出荷値と同じ形: base 10 / drop +2%毎(上限+150%) / exp +1%毎(上限+75%)
        return new DungeonLevelReward(true, 10, 0.02, 1.5, 0.01, 0.75);
    }

    // --- NONE / 無効化 ---

    @Test
    void noneNeverMultiplies() {
        assertTrue(DungeonLevelReward.NONE.isNone());
        assertEquals(1.0, DungeonLevelReward.NONE.dropMultiplierAt(9999));
        assertEquals(1.0, DungeonLevelReward.NONE.expMultiplierAt(9999));
    }

    @Test
    void disabledFlagWinsOverConfiguredNumbers() {
        DungeonLevelReward reward = new DungeonLevelReward(false, 10, 0.02, 1.5, 0.01, 0.75);
        assertTrue(reward.isNone());
        assertEquals(1.0, reward.dropMultiplierAt(80));
        assertEquals(1.0, reward.expMultiplierAt(80));
    }

    @Test
    void nullFieldsAreTreatedAsZeroAndDisableTheirSide() {
        DungeonLevelReward reward = new DungeonLevelReward(true, null, null, null, null, null);
        assertTrue(reward.isNone());
        assertEquals(1.0, reward.dropMultiplierAt(80));
        assertEquals(1.0, reward.expMultiplierAt(80));
    }

    @Test
    void zeroCapDisablesOnlyThatSide() {
        // ドロップ側だけ cap 0 = ドロップは無効、EXPは生きる。
        DungeonLevelReward reward = new DungeonLevelReward(true, 10, 0.02, 0.0, 0.01, 0.75);
        assertFalse(reward.isNone());
        assertEquals(1.0, reward.dropMultiplierAt(40));
        assertEquals(1.3, reward.expMultiplierAt(40), 1e-9);
    }

    // --- base-level の境界 ---

    @Test
    void atOrBelowBaseLevelIsExactlyNeutral() {
        DungeonLevelReward reward = shipped();
        assertEquals(1.0, reward.dropMultiplierAt(0));
        assertEquals(1.0, reward.dropMultiplierAt(10), "base-level ちょうどは超過0なので等倍");
        assertEquals(1.0, reward.expMultiplierAt(10));
    }

    @Test
    void oneLevelAboveBaseAddsExactlyOneStep() {
        DungeonLevelReward reward = shipped();
        assertEquals(1.02, reward.dropMultiplierAt(11), 1e-9);
        assertEquals(1.01, reward.expMultiplierAt(11), 1e-9);
    }

    @Test
    void negativeMobLevelStaysNeutral() {
        // レベル刻印の無いモブは 0 を返すので、負値が来ても等倍で素通りさせる。
        assertEquals(1.0, shipped().dropMultiplierAt(-5));
        assertEquals(1.0, shipped().expMultiplierAt(-5));
    }

    // --- 上限 ---

    @Test
    void bonusIsCappedOnBothSides() {
        DungeonLevelReward reward = shipped();
        // drop: 0.02 * 75 = 1.5 でちょうど頭打ち(base10 + 75 = レベル85)。
        assertEquals(2.5, reward.dropMultiplierAt(85), 1e-9);
        assertEquals(2.5, reward.dropMultiplierAt(300), 1e-9, "頭打ち後はレベルをいくら上げても増えない");
        // exp: 0.01 * 75 = 0.75 で同じレベルで頭打ち。
        assertEquals(1.75, reward.expMultiplierAt(85), 1e-9);
        assertEquals(1.75, reward.expMultiplierAt(300), 1e-9);
    }

    @Test
    void hugeMobLevelDoesNotOverflow() {
        // isNone() が Integer.MAX_VALUE を渡すので、int の引き算で溢れると符号が反転して誤判定になる。
        DungeonLevelReward reward = shipped();
        assertEquals(2.5, reward.dropMultiplierAt(Integer.MAX_VALUE), 1e-9);
        assertFalse(reward.isNone());
    }
}
