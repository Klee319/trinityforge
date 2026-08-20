package com.trinityforge.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-18 ユーザー要望「破壊時EXP系のもらえるバニラ経験値が多すぎる。現状の1/4程度の量にして
 * 設定もできるようにして」の挙動を固定する。
 *
 * <p>旧実装は {@code Math.round(1.0 * (1 + bonus))} で整数化していたため、
 * <b>ベースを 0.25 に下げただけでは四捨五入に飲まれて意図した量にならない</b>
 * (bonus 0 なら 0.25→0 で機能が死に、bonus 1.5 なら 0.625→1 で 1/3 しか減らない)。
 * ここでは「端数を持ち越して期待値どおりに減る」ことを検証する。
 */
class BreakVanillaExpLedgerTest {

    @Test
    @DisplayName("既定値は旧実装(1.0)の1/4")
    void defaultBaseIsAQuarterOfTheOldHardCodedValue() {
        assertEquals(0.25, BreakVanillaExpLedger.DEFAULT_BASE_EXP, 1e-9);
    }

    @Test
    @DisplayName("ボーナス0・既定ベースなら4回壊して1EXP(四捨五入で0に化けない)")
    void quarterBaseGrantsOneExpEveryFourBreaks() {
        BreakVanillaExpLedger ledger = new BreakVanillaExpLedger();
        UUID player = UUID.randomUUID();

        assertEquals(0, ledger.take(player, 0.25, 0.0));
        assertEquals(0, ledger.take(player, 0.25, 0.0));
        assertEquals(0, ledger.take(player, 0.25, 0.0));
        assertEquals(1, ledger.take(player, 0.25, 0.0),
                "端数を持ち越していない(0.25 が毎回0へ丸められると機能ごと死ぬ)");
        assertEquals(0, ledger.take(player, 0.25, 0.0), "持ち越しがリセットされていない");
    }

    @Test
    @DisplayName("100回壊した合計は base×(1+bonus)×100 とほぼ一致する(期待値どおり)")
    void totalOverManyBreaksMatchesTheExpectedValue() {
        BreakVanillaExpLedger ledger = new BreakVanillaExpLedger();
        UUID player = UUID.randomUUID();
        int total = 0;
        for (int i = 0; i < 100; i++) {
            total += ledger.take(player, 0.25, 1.5); // 0.625/回
        }
        assertEquals(62, total, "期待値62.5に対する誤差が1EXPを超えている: " + total);
    }

    @Test
    @DisplayName("旧挙動(ベース1.0・ボーナス1.5=3EXP/回)に対して概ね1/4になる")
    void newDefaultIsRoughlyAQuarterOfTheOldGrant() {
        BreakVanillaExpLedger ledger = new BreakVanillaExpLedger();
        UUID player = UUID.randomUUID();
        int now = 0;
        for (int i = 0; i < 40; i++) {
            now += ledger.take(player, BreakVanillaExpLedger.DEFAULT_BASE_EXP, 1.5);
        }
        // 旧実装は毎回 Math.round(1.0 * 2.5) = 3 を渡していた(名目 2.5 に対し四捨五入で +20% 過剰)。
        int beforeRounded = (int) Math.round(1.0 * (1.0 + 1.5)) * 40; // 実際に配っていた量
        double beforeNominal = 1.0 * (1.0 + 1.5) * 40;               // 式どおりの量
        assertEquals(120, beforeRounded, "旧実装の見積りが変わっている(テストの前提を更新すること)");
        assertEquals(100.0, beforeNominal, 1e-9);
        // 新実装は名目どおり配る(端数持ち越し)ので、名目比で正確に1/4になる。
        assertTrue(Math.abs(now * 4 - beforeNominal) <= 4,
                "名目比で1/4から外れている: 新=" + now + " 旧名目=" + beforeNominal);
        // 実際に配っていた量(四捨五入込み)に対しては1/4より更に軽くなる — 体感の減り方はこちら。
        assertTrue(now * 4 <= beforeRounded,
                "旧実績より重くなっている: 新=" + now + " 旧実績=" + beforeRounded);
    }

    @Test
    @DisplayName("ベース0以下・負のボーナス・null は何も付与しない")
    void degenerateInputsGrantNothing() {
        BreakVanillaExpLedger ledger = new BreakVanillaExpLedger();
        UUID player = UUID.randomUUID();
        assertEquals(0, ledger.take(player, 0.0, 10.0), "ベース0は機能OFFと同じでなければならない");
        assertEquals(0, ledger.take(player, -1.0, 10.0));
        assertEquals(0, ledger.take(null, 1.0, 0.0));
        assertEquals(0, ledger.take(player, Double.NaN, 0.0));
        // 負のボーナスは0扱い(EXPが減る向きの寄与は作らない)。
        assertEquals(1, ledger.take(player, 1.0, -5.0));
    }

    @Test
    @DisplayName("端数はプレイヤーごとに独立で、forget で捨てられる")
    void carryIsPerPlayerAndForgettable() {
        BreakVanillaExpLedger ledger = new BreakVanillaExpLedger();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(0, ledger.take(a, 0.5, 0.0));
        assertEquals(0, ledger.take(b, 0.5, 0.0), "他人の端数を共有している");
        ledger.forget(a);
        assertEquals(0, ledger.take(a, 0.5, 0.0), "forget 後も端数が残っている");
        assertEquals(1, ledger.take(b, 0.5, 0.0));
    }
}
