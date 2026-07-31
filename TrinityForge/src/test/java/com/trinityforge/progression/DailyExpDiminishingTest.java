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
        // 1000 まで等倍 → 1000 超過分 1000 ごとに 0.5 倍 → 下限 0.25
        return new DailyExpDiminishing.Settings(true, 24 * HOUR, 1000.0, 1000.0, 0.5, 0.25, Set.of());
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
    @DisplayName("しきい値までは等倍、超えた分だけ薄まる")
    void decaysOnlyAboveThreshold() {
        AtomicLong now = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();

        // 蓄積 1000 = ちょうど threshold → まだ等倍
        assertEquals(1.0, daily.consume(settings(), player, "MINING", 1000.0));
        // 蓄積 2000 = 1段超過 → 0.5
        assertEquals(0.5, daily.consume(settings(), player, "MINING", 1000.0), 1e-9);
        // 蓄積 3000 = 2段超過 → 0.25（floor と一致）
        assertEquals(0.25, daily.consume(settings(), player, "MINING", 1000.0), 1e-9);
        // それ以上は floor で止まる
        assertEquals(0.25, daily.consume(settings(), player, "MINING", 100_000.0), 1e-9);
    }

    @Test
    @DisplayName("段の境目で急に落ちない（段数を整数に丸めていない）")
    void decayIsContinuous() {
        double justBelow = DailyExpDiminishing.multiplierFor(settings(), 1999.0);
        double justAbove = DailyExpDiminishing.multiplierFor(settings(), 2001.0);

        assertTrue(Math.abs(justBelow - justAbove) < 0.001,
                "1段の境界を跨いでも倍率はほぼ連続であること: " + justBelow + " vs " + justAbove);
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
                true, 24 * HOUR, 1000.0, 1000.0, 0.5, 0.25, Set.of("FISHING"));
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

    @Test
    @DisplayName("floor は Settings 側で 0〜1 にクランプされる（0除算や増幅を作らない）")
    void settingsClampsRanges() {
        DailyExpDiminishing.Settings insane = new DailyExpDiminishing.Settings(
                true, -5.0, -100.0, 0.0, 5.0, 9.0, null);

        assertTrue(insane.windowMillis() >= 1.0);
        assertEquals(0.0, insane.threshold());
        assertTrue(insane.step() >= 1.0);
        assertEquals(1.0, insane.decayPerStep());
        assertEquals(1.0, insane.floor());
        assertTrue(insane.exemptSkills().isEmpty());
    }
}
