package com.trinityforge.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PvP抑制({@link PvpDamagePolicy})の純関数テスト。
 *
 * <p>守りたい性質は「攻撃力がどれだけ指数で伸びても、PvPは最低 1/割合上限 発かかる」こと。
 * 倍率だけのテストにすると、攻撃カーブを触ったときに壊れたことに気づけない。
 */
class PvpDamagePolicyTest {

    private static final double MULT = 0.5;
    private static final double MAX_PCT = 0.15;
    private static final double PLAYER_MAX_HP = 33.0;

    @Test
    @DisplayName("無効化すると素通しになる(従来挙動)")
    void disabled_passesThrough() {
        assertEquals(1052.0, PvpDamagePolicy.apply(1052.0, PLAYER_MAX_HP, false, MULT, MAX_PCT));
    }

    @Test
    @DisplayName("上限に届かない低ダメージには倍率だけが掛かる")
    void belowCap_multiplierOnly() {
        // 8 * 0.5 = 4.0 で、上限 33*0.15 = 4.95 に届かない
        assertEquals(4.0, PvpDamagePolicy.apply(8.0, PLAYER_MAX_HP, true, MULT, MAX_PCT));
    }

    @Test
    @DisplayName("Lv100帯の火力でも1発は最大体力の割合上限で頭打ちになる")
    void highLevel_cappedByMaxHealthFraction() {
        assertEquals(PLAYER_MAX_HP * MAX_PCT,
                PvpDamagePolicy.apply(1052.0, PLAYER_MAX_HP, true, MULT, MAX_PCT), 1e-9);
    }

    @Test
    @DisplayName("上限はスケールフリー: 攻撃力が10倍になっても必要発数は変わらない")
    void capIsScaleFree() {
        double once = PvpDamagePolicy.apply(1052.0, PLAYER_MAX_HP, true, MULT, MAX_PCT);
        double tenFold = PvpDamagePolicy.apply(10520.0, PLAYER_MAX_HP, true, MULT, MAX_PCT);
        assertEquals(once, tenFold, 1e-9);
        // 「倒すのに最低7発」= 1/0.15 の切り上げ
        assertEquals(7, (int) Math.ceil(PLAYER_MAX_HP / once));
    }

    @Test
    @DisplayName("割合上限0は上限なし(倍率だけ)")
    void zeroPercent_disablesCapOnly() {
        assertEquals(526.0, PvpDamagePolicy.apply(1052.0, PLAYER_MAX_HP, true, MULT, 0.0));
    }

    @Test
    @DisplayName("最大体力が読めない環境では倍率だけが効き、上限は無効化される(安全側)")
    void unknownMaxHealth_capSkipped() {
        assertEquals(526.0, PvpDamagePolicy.apply(1052.0, 0.0, true, MULT, MAX_PCT));
    }

    @Test
    @DisplayName("倍率0は対人ダメージ0(実質PvP禁止)")
    void zeroMultiplier_blocksPvp() {
        assertEquals(0.0, PvpDamagePolicy.apply(1052.0, PLAYER_MAX_HP, true, 0.0, MAX_PCT));
    }

    @Test
    @DisplayName("負のダメージ(=回復)は抑制しない")
    void negativeDamage_untouched() {
        assertEquals(-12.0, PvpDamagePolicy.apply(-12.0, PLAYER_MAX_HP, true, MULT, MAX_PCT));
    }
}
