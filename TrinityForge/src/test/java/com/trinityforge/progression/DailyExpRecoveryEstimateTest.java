package com.trinityforge.progression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日次逓減の<b>「等倍まであと◯時間」の表示</b>が、W-154 の強制解除（発動から
 * {@code lock-release-hours}）を織り込んでいることを固定する
 * （2026-08-21 実サーバ報告「24時間経験値減衰の仕様に関して正しく時間経過でリセットされているが
 * 表示が前のデータのまま？なのか、リセットまで24時間を超えているらしい」）。
 *
 * <p><b>着手前の状態</b>: 回復の見積りは指数減衰だけを解いた
 * {@code 窓 × ln(蓄積 / perAmount)} で、<b>強制解除を1つも見ていなかった</b>。
 * 出荷設定（窓 24h・perAmount 10万）では蓄積 30万で 26 時間、100万で 55 時間 ──
 * <b>実際には遅くとも発動から 24 時間で等倍へ戻る</b>のに、画面には 24 時間を超える数字が出ていた。
 * 減衰そのものは正しく動いていたので、ズレていたのは表示だけ（報告の「リセットまで24時間を
 * 超えているらしい」はこれで説明が付く）。
 */
class DailyExpRecoveryEstimateTest {

    private static final double HOUR = 3_600_000.0;
    private static final double RELEASE_HOURS = 24.0;

    /** 出荷 {@code stats/skill-exp.yml} の {@code daily-diminishing} と同じ形。 */
    private static DailyExpDiminishing.Settings shipped() {
        return new DailyExpDiminishing.Settings(
                true, 24 * HOUR, 100_000.0, 0.9, 0.5, Set.of(), RELEASE_HOURS * HOUR);
    }

    /** 期限なし（W-154 以前の挙動）。 */
    private static DailyExpDiminishing.Settings withoutRelease() {
        return new DailyExpDiminishing.Settings(true, 24 * HOUR, 100_000.0, 0.9, 0.5, Set.of());
    }

    @Test
    @DisplayName("等倍までの表示は強制解除の期限を超えない（報告そのもの）")
    void theRecoveryEstimateNeverExceedsTheForcedRelease() {
        AtomicLong now = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();

        // 蓄積100万。指数減衰だけで解くと 24h × ln(10) ≒ 55.3 時間になる。
        daily.consume(shipped(), player, "MINING", 1_000_000.0);
        DailyExpDiminishing.Status status = daily.status(shipped(), player, "MINING");

        assertTrue(status.multiplier() < 1.0, "前提: この時点で逓減が掛かっていること");
        assertEquals(RELEASE_HOURS * HOUR, status.millisUntilFull(), 1.0,
                "発動直後なら、等倍まではちょうど解除までの残り時間になるはず");
        // 指数減衰だけの見積り(修正前の値)が本当に24時間を超えることを、同じ設定で示しておく
        // ── 「表示が長すぎた」という主張の根拠を、テストの中に置いておくため。
        double exponentialOnly =
                DailyExpDiminishing.statusOf(shipped(), 1_000_000.0).millisUntilFull();
        assertTrue(exponentialOnly > RELEASE_HOURS * HOUR,
                "前提が崩れている: 指数減衰だけの見積りが期限を超えていない");
    }

    @Test
    @DisplayName("表示は時間とともに減っていく（発動から12時間なら残り12時間）")
    void theEstimateCountsDownTowardsTheRelease() {
        AtomicLong now = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();

        daily.consume(shipped(), player, "MINING", 1_000_000.0);
        now.set((long) (12 * HOUR));

        DailyExpDiminishing.Status status = daily.status(shipped(), player, "MINING");
        assertEquals(12 * HOUR, status.millisUntilFull(), 1.0);
        assertTrue(status.multiplier() < 1.0, "12時間ではまだ解除されない");
    }

    @Test
    @DisplayName("指数減衰の方が早いときはそちらを出す（期限で頭打ちにするだけで、遅くしない）")
    void theSoonerOfTheTwoIsShown() {
        AtomicLong now = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();

        // 蓄積15万 = 1段だけ。24h × ln(1.5) ≒ 9.73 時間で自然に戻るので、期限(24h)より早い。
        daily.consume(shipped(), player, "MINING", 150_000.0);

        double expected = 24 * HOUR * Math.log(1.5);
        assertEquals(expected, daily.status(shipped(), player, "MINING").millisUntilFull(), 1.0,
                "期限より早く戻れるケースまで期限へ引き延ばしてはいけない");
        assertTrue(expected < RELEASE_HOURS * HOUR, "前提: この蓄積は期限より早く戻る");
    }

    @Test
    @DisplayName("1段よくなるまでの表示も期限で頭打ちにする")
    void theNextImprovementEstimateIsCappedToo() {
        AtomicLong now = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();

        // 下限(0.5)へ叩き落とす。この領域は段が減っても倍率が動かないので、指数側の見積りは長い
        // (あるいは出せない)が、期限が来れば一気に等倍へ戻る。
        daily.consume(shipped(), player, "MINING", 5_000_000.0);
        DailyExpDiminishing.Status status = daily.status(shipped(), player, "MINING");

        assertEquals(0.5, status.multiplier(), 1e-9, "前提: 下限に張り付いていること");
        assertTrue(status.millisUntilImproved() > 0.0
                        && status.millisUntilImproved() <= RELEASE_HOURS * HOUR + 1.0,
                "下限に張り付いていても、期限までには必ずよくなる: "
                        + status.millisUntilImproved());
    }

    @Test
    @DisplayName("期限なし設定では従来どおり（後方互換。24時間を超える見積りが出てよい）")
    void withoutAReleaseTheEstimateIsUnchanged() {
        AtomicLong now = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();

        daily.consume(withoutRelease(), player, "MINING", 1_000_000.0);

        assertEquals(24 * HOUR * Math.log(10.0),
                daily.status(withoutRelease(), player, "MINING").millisUntilFull(), 1.0,
                "期限を設定していない環境の挙動を変えてはいけない");
    }

    @Test
    @DisplayName("期限切れの窓は蓄積0・等倍で読む（実際の付与と表示が食い違わない）")
    void anExpiredWindowReadsAsFullRate() {
        AtomicLong now = new AtomicLong(0L);
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();

        daily.consume(shipped(), player, "MINING", 1_000_000.0);
        now.set((long) (RELEASE_HOURS * HOUR) + 1L);

        DailyExpDiminishing.Status status = daily.status(shipped(), player, "MINING");
        assertEquals(1.0, status.multiplier(), 1e-9);
        assertEquals(0.0, status.accumulated(), 1e-9);
        assertEquals(-1.0, status.millisUntilFull(), 1e-9, "等倍なら「戻るまで」の行は出さない");
    }
}
