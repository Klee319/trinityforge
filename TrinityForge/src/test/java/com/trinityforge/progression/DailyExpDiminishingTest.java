package com.trinityforge.progression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日次EXP逓減の契約を固定する。
 *
 * <p>ここで守りたいのは 3 点:
 * <ul>
 *   <li>既定(無効)では 1.0 のまま = config を書くまで実サーバの挙動が変わらない</li>
 *   <li>スキルごとに独立 = 採掘を掘り切った日でも伐採は等倍（縦を伸ばさず横へ促す設計の根拠）</li>
 *   <li>時間経過で戻る、かつ日付境界のような段差が無い</li>
 * </ul>
 */
class DailyExpDiminishingTest {

    private static final double HOUR = 3_600_000.0;

    private static DailyExpDiminishing.Settings settings() {
        // 1000 稼ぐごとに 0.5 倍(離散) → 下限 0.25
        return new DailyExpDiminishing.Settings(true, 24 * HOUR, 1000.0, 0.5, 0.25, Set.of());
    }

    @Test
    @DisplayName("無効なら常に1.0（config未記入で挙動が変わらないこと）")
    void disabledIsAlwaysOne() {
        DailyExpDiminishing daily = new DailyExpDiminishing(() -> 0L);
        UUID player = UUID.randomUUID();

        for (int i = 0; i < 50; i++) {
            assertEquals(1.0, daily.consume(DailyExpDiminishing.Settings.DISABLED, player, "MINING", 10_000.0));
        }
        assertEquals(1.0, daily.consume(null, player, "MINING", 10_000.0));
    }

    @Test
    @DisplayName("per-amount に達するたびに1段ずつ薄まる")
    void decaysOneStepEachTimeThePerAmountIsReached() {
        AtomicLong now = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();

        // 蓄積 999 = まだ1段目に達していない → 等倍
        assertEquals(1.0, daily.consume(settings(), player, "MINING", 999.0), 1e-9);
        // 蓄積 1999 → floor(1999/1000)=1段 → 0.5
        assertEquals(0.5, daily.consume(settings(), player, "MINING", 1000.0), 1e-9);
        // 蓄積 2999 → 2段 → 0.25（floor と一致）
        assertEquals(0.25, daily.consume(settings(), player, "MINING", 1000.0), 1e-9);
        // それ以上は floor で止まる
        assertEquals(0.25, daily.consume(settings(), player, "MINING", 100_000.0), 1e-9);
    }

    @Test
    @DisplayName("段は離散。境界を跨いだ瞬間に1段落ちる（2026-08-01 仕様変更）")
    void decayStepsDownDiscretelyAtEachBoundary() {
        // 旧仕様は「段数を丸めない連続式」だったが、プレイヤーが「あと何EXPで落ちるか」を
        // 数えられないので離散へ変更した。境界の手前と後で必ず段が変わる。
        assertEquals(1.0, DailyExpDiminishing.multiplierFor(settings(), 999.0), 1e-9);
        assertEquals(0.5, DailyExpDiminishing.multiplierFor(settings(), 1000.0), 1e-9);
        assertEquals(0.5, DailyExpDiminishing.multiplierFor(settings(), 1999.0), 1e-9);
        assertEquals(0.25, DailyExpDiminishing.multiplierFor(settings(), 2000.0), 1e-9);

        // 次の段まであと何EXPかを出せること（表示用）。
        assertEquals(1.0, DailyExpDiminishing.untilNextStep(settings(), 999.0), 1e-9);
        assertEquals(500.0, DailyExpDiminishing.untilNextStep(settings(), 1500.0), 1e-9);
        // 下限へ張り付いたら -1（もう落ちないので案内しない）。
        assertEquals(-1.0, DailyExpDiminishing.untilNextStep(settings(), 99_999.0), 1e-9);
    }

    @Test
    @DisplayName("スキルごとに独立して数える")
    void perSkillIndependence() {
        AtomicLong now = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();

        daily.consume(settings(), player, "MINING", 5000.0);

        // 採掘を掘り切っても伐採は等倍のまま
        assertEquals(1.0, daily.consume(settings(), player, "WOODCUTTING", 900.0), 1e-9);
    }

    @Test
    @DisplayName("プレイヤーごとに独立して数える")
    void perPlayerIndependence() {
        DailyExpDiminishing daily = new DailyExpDiminishing(() -> 0L);
        UUID heavy = UUID.randomUUID();
        UUID casual = UUID.randomUUID();

        daily.consume(settings(), heavy, "MINING", 10_000.0);

        assertEquals(1.0, daily.consume(settings(), casual, "MINING", 900.0), 1e-9);
    }

    @Test
    @DisplayName("時間が経つと蓄積が減衰して倍率が戻る")
    void recoversOverTime() {
        AtomicLong now = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();

        daily.consume(settings(), player, "MINING", 4000.0);
        double immediately = DailyExpDiminishing.multiplierFor(settings(), daily.accumulated(settings(), player, "MINING"));

        now.addAndGet((long) (48 * HOUR));
        double afterTwoDays = DailyExpDiminishing.multiplierFor(settings(), daily.accumulated(settings(), player, "MINING"));

        assertTrue(immediately < 0.3, "直後は薄まっている: " + immediately);
        assertEquals(1.0, afterTwoDays, 1e-9, "2日空ければ完全に戻る");
    }

    @Test
    @DisplayName("exempt-skills のスキルは逓減しない")
    void exemptSkillsAreUntouched() {
        DailyExpDiminishing.Settings exempting = new DailyExpDiminishing.Settings(
                true, 24 * HOUR, 1000.0, 0.5, 0.25, Set.of("FISHING"));
        DailyExpDiminishing daily = new DailyExpDiminishing(() -> 0L);
        UUID player = UUID.randomUUID();

        for (int i = 0; i < 20; i++) {
            assertEquals(1.0, daily.consume(exempting, player, "FISHING", 10_000.0));
        }
    }

    @Test
    @DisplayName("forget でプレイヤーの状態が消える（メモリを有界に保つ）")
    void forgetDropsState() {
        DailyExpDiminishing daily = new DailyExpDiminishing(() -> 0L);
        UUID player = UUID.randomUUID();

        daily.consume(settings(), player, "MINING", 5000.0);
        assertEquals(1, daily.trackedPlayers());

        daily.forget(player);

        assertEquals(0, daily.trackedPlayers());
        assertEquals(1.0, daily.consume(settings(), player, "MINING", 900.0), 1e-9);
    }

    // --- 2026-08-18: プレイヤーへ見せるための表示・通知系 -------------------------------------------

    @Test
    @DisplayName("consumeDetailed は段が落ちた／戻った瞬間を報告する（通知の発火条件）")
    void consumeDetailedReportsStepChanges() {
        AtomicLong now = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();

        // 蓄積 900: まだ1段目に届かない → 何も動かない
        DailyExpDiminishing.Applied first = daily.consumeDetailed(settings(), player, "MINING", 900.0);
        assertTrue(!first.worsened() && !first.improved(), "段が動いていないのに通知が飛ぶ");

        // 蓄積 1400: 1段落ちた瞬間
        DailyExpDiminishing.Applied dropped = daily.consumeDetailed(settings(), player, "MINING", 500.0);
        assertTrue(dropped.worsened(), "段が落ちたのに報告されない");
        assertEquals(0.5, dropped.multiplier(), 1e-9);
        assertEquals(1.0, dropped.previousMultiplier(), 1e-9);

        // 同じ段の中で稼いでも通知しない（毎回流すとチャットが埋まって読まれなくなる）
        DailyExpDiminishing.Applied same = daily.consumeDetailed(settings(), player, "MINING", 100.0);
        assertTrue(!same.worsened() && !same.improved(), "同じ段のあいだは通知しない");

        // 十分に時間が経つと戻る。比較対象が「減衰だけ適用した値」でないと、
        // 戻ったぶんが次の付与の増分に埋もれて improved が一度も立たない。
        now.addAndGet((long) (48 * HOUR));
        DailyExpDiminishing.Applied recovered = daily.consumeDetailed(settings(), player, "MINING", 1.0);
        assertTrue(recovered.improved(), "時間経過で戻ったのに報告されない");
        assertEquals(1.0, recovered.multiplier(), 1e-9);
    }

    @Test
    @DisplayName("回復までの時間は「倍率が実際に上がる段」まで数える（下限で潰れた段を飛ばす）")
    void recoveryEstimateSkipsStepsFlattenedByTheFloor() {
        // settings(): per=1000 / decay=0.5 / floor=0.25 → 2段(0.25)で既に下限。
        // 蓄積 5000(=5段)から見ると、4段(0.0625)も3段(0.125)も下限クランプで 0.25 のまま動かない。
        // 素朴に「1段減るまで」を出すと『あと少しで回復』と言ったのに何も変わらない嘘になる。
        assertEquals(0.25, DailyExpDiminishing.multiplierFor(settings(), 5000.0), 1e-9,
                "前提: 5段目は下限に張り付いている");
        assertEquals(0.25, DailyExpDiminishing.multiplierFor(settings(), 2500.0), 1e-9,
                "前提: 1段減らしても倍率は動かない");

        // 実際に 0.25 -> 0.5 へ戻るのは、蓄積が 2000 を割って1段になったとき。
        assertEquals(24 * HOUR * Math.log(5000.0 / 2000.0),
                DailyExpDiminishing.millisUntilNextImprovement(settings(), 5000.0), 1.0);

        assertEquals(-1.0, DailyExpDiminishing.millisUntilNextImprovement(settings(), 999.0), 1e-9,
                "等倍なら案内しない");
    }

    @Test
    @DisplayName("等倍へ戻るまでの時間は、蓄積が per-amount を割るまでの指数減衰で出す")
    void fullRecoveryEstimateUsesTheDecayWindow() {
        assertEquals(24 * HOUR * Math.log(4000.0 / 1000.0),
                DailyExpDiminishing.millisUntilFullRecovery(settings(), 4000.0), 1.0);
        assertEquals(-1.0, DailyExpDiminishing.millisUntilFullRecovery(settings(), 500.0), 1e-9);
    }

    @Test
    @DisplayName("表示用スナップショットは倍率・残りEXP・回復時間を1回で返す")
    void statusBundlesEverythingTheUiNeeds() {
        DailyExpDiminishing.Status full = DailyExpDiminishing.statusOf(settings(), 400.0);
        assertTrue(full.atFullRate());
        assertEquals(600.0, full.expUntilNextStep(), 1e-9, "あと600で1段落ちる");
        assertEquals(-1.0, full.millisUntilFull(), 1e-9);

        DailyExpDiminishing.Status diminished = DailyExpDiminishing.statusOf(settings(), 1500.0);
        assertEquals(0.5, diminished.multiplier(), 1e-9);
        assertTrue(!diminished.atFullRate());
        assertEquals(500.0, diminished.expUntilNextStep(), 1e-9);
        assertTrue(diminished.millisUntilFull() > 0.0);
    }

    @Test
    @DisplayName("表示文字列は全画面で同じ整形を通す（GUIとチャットで数字が食い違わないこと）")
    void displayTextIsFormattedInOnePlace() {
        assertEquals("70%", DailyExpRateText.percent(0.7));
        assertEquals("34%", DailyExpRateText.percent(0.343));
        assertEquals("×50%", DailyExpRateText.badge(DailyExpDiminishing.statusOf(settings(), 1500.0)));
        assertEquals(null, DailyExpRateText.badge(DailyExpDiminishing.statusOf(settings(), 10.0)),
                "等倍のときにバッジを出すと常時ノイズになる");

        assertEquals("約30分", DailyExpRateText.duration(30 * 60_000.0));
        assertEquals("約2.0時間", DailyExpRateText.duration(2 * HOUR));
        assertEquals("まもなく", DailyExpRateText.duration(10_000.0));
        assertEquals(null, DailyExpRateText.duration(-1.0), "該当なしは行ごと落とせるように null");
        assertEquals("42,300", DailyExpRateText.exp(42_300.0));
    }

    @Test
    @DisplayName("floor は Settings 側で 0〜1 にクランプされる（0除算や増幅を作らない）")
    void settingsClampsRanges() {
        DailyExpDiminishing.Settings insane = new DailyExpDiminishing.Settings(
                true, -5.0, -100.0, 5.0, 9.0, null);

        assertTrue(insane.windowMillis() >= 1.0);
        assertTrue(insane.perAmount() >= 1.0);
        assertEquals(1.0, insane.decayPerAmount());
        assertEquals(1.0, insane.floor());
        assertTrue(insane.exemptSkills().isEmpty());
    }
}
