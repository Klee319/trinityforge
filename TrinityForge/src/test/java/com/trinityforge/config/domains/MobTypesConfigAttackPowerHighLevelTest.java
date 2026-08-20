package com.trinityforge.config.domains;

import com.trinityforge.config.domains.MobTypesConfig.AttackPowerHighLevelPhase;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code level-coefficients.attack.attack-power-high-level-from / -per-level} の解析(2026-08-03、
 * 要件#63の残り「フィールドモブの攻撃力に高レベル区間が無い」)。
 *
 * <p>Bukkit サーバーを一切必要としない純粋なパースのテスト。ここで縛るのは主に
 * <b>「未設定なら絶対に何も足さない」</b>という後方互換の性質で、これが崩れると
 * dimensions を書いていない既存 config のモブ全部の攻撃力が黙って変わる。
 */
class MobTypesConfigAttackPowerHighLevelTest {

    private static final double DELTA = 1.0e-9;

    private static AttackPowerHighLevelPhase parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return MobTypesConfig.parseAttackPowerHighLevel(cfg.getConfigurationSection("level-coefficients"));
    }

    @Test
    @DisplayName("level-coefficients ごと無い場合は NONE(＝どのレベルでも 0 を足す、従来どおり)")
    void missingSectionYieldsNoOp() {
        AttackPowerHighLevelPhase phase = MobTypesConfig.parseAttackPowerHighLevel(null);
        assertFalse(phase.isActive(), "未設定が active になると刻印条件まで変わる");
        assertEquals(0.0, phase.bonusAt(100), DELTA, "未設定なら Lv100 でも一切足さないこと");
    }

    @Test
    @DisplayName("attack: 節はあるが高レベル区間のキーが無い場合も NONE(既存 config の後方互換)")
    void attackSectionWithoutHighLevelKeysYieldsNoOp() throws Exception {
        AttackPowerHighLevelPhase phase = parse("""
                level-coefficients:
                  attack:
                    attack-power: 0
                    attack-power-growth: 1.033
                    attack-power-growth-interval: 1.0
                """);
        assertFalse(phase.isActive(), "growth だけ書いた既存 config が active になってはいけない");
        assertEquals(0.0, phase.bonusAt(100), DELTA, "Lv100 でも一切足さないこと");
    }

    @Test
    @DisplayName("from/per-level を書くと解析され、Lv45 未満は 0・Lv60/80/100 で期待どおり増える")
    void parsedPhaseAddsOnlyAboveTheBreakpoint() throws Exception {
        AttackPowerHighLevelPhase phase = parse("""
                level-coefficients:
                  attack:
                    attack-power: 0
                    attack-power-growth: 1.033
                    attack-power-growth-interval: 1.0
                    attack-power-high-level-from: 45
                    attack-power-high-level-per-level: 0.25
                """);

        assertTrue(phase.isActive(), "書いたのに active でないと攻撃ステの刻印自体が発火しない");
        assertEquals(45.0, phase.from(), DELTA);
        assertEquals(0.25, phase.perLevel(), DELTA);

        assertEquals(0.0, phase.bonusAt(0), DELTA, "Lv0 は完全に無干渉");
        assertEquals(0.0, phase.bonusAt(44), DELTA, "開始レベル直前は 0");
        assertEquals(0.0, phase.bonusAt(45), DELTA, "開始レベルちょうどは 0(境界で不連続にならない)");
        assertEquals(0.25, phase.bonusAt(46), DELTA, "開始レベル+1 で per-level 1つ分");
        assertEquals(3.75, phase.bonusAt(60), DELTA, "Lv60 は 0.25 × 15");
        assertEquals(8.75, phase.bonusAt(80), DELTA, "Lv80 は 0.25 × 35");
        assertEquals(13.75, phase.bonusAt(100), DELTA, "Lv100 は 0.25 × 55");
    }

    @Test
    @DisplayName("per-level だけ書いて from を書かない場合は発動しない(誤設定で全レベルに乗らない)")
    void perLevelWithoutFromNeverTriggers() throws Exception {
        AttackPowerHighLevelPhase phase = parse("""
                level-coefficients:
                  attack:
                    attack-power-high-level-per-level: 0.25
                """);
        assertFalse(phase.isActive(), "from 未設定は「発動しない」側へ倒すこと");
        assertEquals(0.0, phase.bonusAt(100), DELTA);
    }

    @Test
    @DisplayName("不正値(NaN / -Infinity)は「発動しない」側へ倒れ、無限大の攻撃力にならない")
    void nonFiniteValuesFallBackToNoOp() {
        assertEquals(0.0, new AttackPowerHighLevelPhase(Double.NaN, 0.25).bonusAt(100), DELTA);
        assertEquals(0.0, new AttackPowerHighLevelPhase(Double.NEGATIVE_INFINITY, 0.25).bonusAt(100),
                DELTA, "-Infinity を from にすると全レベルで無限に足す事故になる");
        assertEquals(0.0, new AttackPowerHighLevelPhase(45.0, Double.NaN).bonusAt(100), DELTA);
    }

    @Test
    @DisplayName("負のレベルは 0 として扱う(スケーリング側と同じ丸め)")
    void negativeLevelIsTreatedAsZero() {
        AttackPowerHighLevelPhase phase = new AttackPowerHighLevelPhase(45.0, 0.25);
        assertEquals(0.0, phase.bonusAt(-10), DELTA);
    }
}
