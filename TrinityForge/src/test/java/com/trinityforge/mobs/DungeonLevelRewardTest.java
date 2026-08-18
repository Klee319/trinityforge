package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DungeonLevelReward} の純粋ロジック(2026-08-18 W-80)。
 *
 * <p>2026-08-18 のユーザー指示で「pivot 未満は規定値より少なく、超えたら多く」「刻みは5レベル単位」
 * という形へ変えた。ここで固定するのはその2点と、頭打ち・無効化・溢れの扱い。
 */
class DungeonLevelRewardTest {

    /** 出荷 yml と同じ形の設定(値の同期は {@link ShippedDungeonLevelRewardTest} が見る)。 */
    private static DungeonLevelReward shaped() {
        return new DungeonLevelReward(true, 35, 5, 0.08, 0.5, 0.3, 0.04, 0.25, 0.2);
    }

    @Test
    void pivotLevelIsExactlyNeutral() {
        DungeonLevelReward reward = shaped();
        assertEquals(1.0, reward.dropMultiplierAt(35), 1e-9, "pivot ちょうどは規定値");
        assertEquals(1.0, reward.expMultiplierAt(35), 1e-9);
    }

    @Test
    void levelsBelowThePivotPayLessThanTheBaseline() {
        // 「低いレベルでは規定値より少なくし」── ここが 1.0 に戻ると頭打ちを下げた意味が消える。
        DungeonLevelReward reward = shaped();
        assertEquals(0.92, reward.dropMultiplierAt(30), 1e-9, "1段下 = -8%");
        assertEquals(0.96, reward.expMultiplierAt(30), 1e-9, "1段下 = -4%");
        assertTrue(reward.dropMultiplierAt(20) < reward.dropMultiplierAt(30), "下るほど減る");
    }

    @Test
    void levelsAboveThePivotPayMoreThanTheBaseline() {
        DungeonLevelReward reward = shaped();
        assertEquals(1.08, reward.dropMultiplierAt(40), 1e-9, "1段上 = +8%");
        assertEquals(1.04, reward.expMultiplierAt(40), 1e-9, "1段上 = +4%");
        assertEquals(1.24, reward.dropMultiplierAt(50), 1e-9, "3段上 = +24%");
        assertEquals(1.12, reward.expMultiplierAt(50), 1e-9);
    }

    @Test
    void theCurveIsAStaircaseWithFiveLevelTreads() {
        // 「5lv単位で変わる」── 難易度 normal/hard/mythic がモブレベルを ∓5 動かすので、
        // ここが連続関数だと難易度の差が端数になって選ぶ動機にならない。
        DungeonLevelReward reward = shaped();
        for (int level = 40; level <= 44; level++) {
            assertEquals(1.08, reward.dropMultiplierAt(level), 1e-9,
                    "レベル" + level + " は 40〜44 と同じ段でなければならない");
        }
        assertEquals(1.16, reward.dropMultiplierAt(45), 1e-9, "45で次の段へ上がる");
    }

    @Test
    void oneDifficultyStepIsExactlyOneRewardStep() {
        // フォーク側が normal を -5 / mythic を +5 するので、同じ挑戦レベル50を選んだときの
        // 実効モブレベルは 45 / 50 / 55。1段ずつずれることを固定する。
        DungeonLevelReward reward = shaped();
        assertEquals(1.16, reward.dropMultiplierAt(45), 1e-9, "normal");
        assertEquals(1.24, reward.dropMultiplierAt(50), 1e-9, "hard");
        assertEquals(1.32, reward.dropMultiplierAt(55), 1e-9, "mythic");
    }

    @Test
    void bothSidesAreCapped() {
        DungeonLevelReward reward = shaped();
        assertEquals(1.5, reward.dropMultiplierAt(70), 1e-9, "増加の頭打ち");
        assertEquals(1.5, reward.dropMultiplierAt(10_000), 1e-9);
        assertEquals(1.25, reward.expMultiplierAt(70), 1e-9);
        assertEquals(0.7, reward.dropMultiplierAt(15), 1e-9, "減少の頭打ち");
        assertEquals(0.8, reward.expMultiplierAt(10), 1e-9);
    }

    @Test
    void extremeLevelsDoNotOverflow() {
        DungeonLevelReward reward = shaped();
        assertEquals(1.5, reward.dropMultiplierAt(Integer.MAX_VALUE), 1e-9);
        assertEquals(0.7, reward.dropMultiplierAt(Integer.MIN_VALUE), 1e-9);
        assertEquals(0.8, reward.expMultiplierAt(Integer.MIN_VALUE), 1e-9);
    }

    @Test
    void disabledWinsOverEveryOtherValue() {
        DungeonLevelReward reward = new DungeonLevelReward(false, 35, 5, 0.08, 0.5, 0.3, 0.04, 0.25, 0.2);
        assertTrue(reward.isNone());
        assertEquals(1.0, reward.dropMultiplierAt(100), 1e-9);
        assertEquals(1.0, reward.expMultiplierAt(1), 1e-9);
    }

    @Test
    void nullFieldsBehaveLikeZero() {
        DungeonLevelReward reward = new DungeonLevelReward(true, null, null, null, null, null, null, null, null);
        assertTrue(reward.isNone());
        assertEquals(1.0, reward.dropMultiplierAt(100), 1e-9);
    }

    @Test
    void zeroCapsDisableOnlyTheirOwnSide() {
        // 増加だけ切って減少は残す(= 高レベルで増えないが低レベルでは減る)設定も書けること。
        DungeonLevelReward reward = new DungeonLevelReward(true, 35, 5, 0.08, 0.0, 0.3, 0.04, 0.25, 0.2);
        assertEquals(1.0, reward.dropMultiplierAt(100), 1e-9, "増加側だけ無効");
        assertEquals(0.92, reward.dropMultiplierAt(30), 1e-9, "減少側は生きている");
        assertEquals(1.25, reward.expMultiplierAt(100), 1e-9, "EXP側は無関係に効く");
        assertFalse(reward.isNone());
    }

    @Test
    void stepFallsBackToFiveWhenUnset() {
        DungeonLevelReward reward = new DungeonLevelReward(true, 35, 0, 0.08, 0.5, 0.3, 0.04, 0.25, 0.2);
        assertEquals(1.08, reward.dropMultiplierAt(40), 1e-9, "step 未設定でも5レベル刻み");
        assertEquals(1.0, reward.dropMultiplierAt(39), 1e-9);
    }

    @Test
    void multiplierNeverReachesZeroEvenWithAnAbsurdPenaltyCap() {
        // schema は penalty-cap を 0.9 までに制限しているが、直呼びの経路も守る。
        DungeonLevelReward reward = new DungeonLevelReward(true, 35, 5, 0.5, 0.5, 5.0, 0.5, 0.5, 5.0);
        assertTrue(reward.dropMultiplierAt(1) > 0.0, "報酬が0や負になってはいけない");
        assertEquals(0.1, reward.dropMultiplierAt(1), 1e-9);
    }
}
