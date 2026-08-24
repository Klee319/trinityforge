package com.trinityforge.progression;

import com.trinityforge.items.ExpCleanseTonic;
import com.trinityforge.progression.infrastructure.sqlite.DailyExpWindowStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「EXP解呪の良薬」（{@link ExpCleanseTonic}）が日次逓減を実際に引き戻すことを固定する
 * （2026-08-24 ユーザー要望）。
 *
 * <p>設定は<b>出荷値と同じ</b>（{@code stats/skill-exp.yml}: 窓24時間 / 10万EXPごとに1段 /
 * 1段 0.9倍 / 下限 0.5）。ここを作り物の値にすると、下で固定している2つの罠
 * （下限クランプ・DBの「大きい方」）がどちらも再現しない。
 */
class ExpCleanseTonicReliefTest {

    private static final double HOUR = 3_600_000.0;
    private static final double PER = 100_000.0;

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private DailyExpWindowStore store;

    private static DailyExpDiminishing.Settings settings() {
        return new DailyExpDiminishing.Settings(true, 24 * HOUR, PER, 0.9, 0.5, Set.of(), 24 * HOUR);
    }

    @BeforeEach
    void openStore() throws SQLException {
        store = new DailyExpWindowStore("jdbc:sqlite::memory:", now::get);
    }

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    private DailyExpWindowPersistence persistence(DailyExpDiminishing daily) {
        return new DailyExpWindowPersistence(daily, store, ExpCleanseTonicReliefTest::settings,
                message -> { throw new AssertionError("永続化が失敗した: " + message); });
    }

    @Test
    @DisplayName("良薬3種の目標倍率は 50% / 75% / 90%（数字の向きを取り違えると罰になる）")
    void tonicsTargetTheKeptRateNotTheLostRate() {
        // ユーザー指定は「10%(-40%) / 25%(-25%) / 50%(初期値) まで減らせるもの」＝【減る側】の上限。
        // 出荷の下限 0.5 が「ペナルティ50%が初期値」なので、10%まで削れる良薬＝倍率90%。
        assertEquals(0.50, ExpCleanseTonic.LESSER.targetMultiplier(), 1e-9);
        assertEquals(0.75, ExpCleanseTonic.GREATER.targetMultiplier(), 1e-9);
        assertEquals(0.90, ExpCleanseTonic.SUPREME.targetMultiplier(), 1e-9);
        for (ExpCleanseTonic tonic : ExpCleanseTonic.values()) {
            assertTrue(tonic.targetMultiplier() >= 0.5,
                    tonic + " が下限(0.5)より低い＝飲むと損をする向きになっている");
        }
    }

    @Test
    @DisplayName("切り下げ先はその倍率を保てる最大量。1段でも超えると目標を割る")
    void maxAmountForIsTheLargestAmountThatStillKeepsTheTarget() {
        for (ExpCleanseTonic tonic : ExpCleanseTonic.values()) {
            double cap = DailyExpDiminishing.maxAmountFor(settings(), tonic.targetMultiplier());
            assertTrue(DailyExpDiminishing.multiplierFor(settings(), cap) >= tonic.targetMultiplier(),
                    tonic + ": 切り下げ先で目標倍率に届いていない");
            assertTrue(DailyExpDiminishing.multiplierFor(settings(), cap + PER) < tonic.targetMultiplier()
                            || tonic.targetMultiplier() <= settings().floor(),
                    tonic + ": 1段ぶん多く残しても目標に届く＝削りすぎ");
        }
    }

    /**
     * <b>この1件が一番落としやすい。</b> 倍率は下限 {@code floor} でクランプされるので、
     * 「倍率が 0.5 以上になる蓄積量」を素直に解くと<b>上限なし＝何もしない</b>になり、
     * 並（50%）の良薬が無言で効果ゼロになる。生の曲線で段数を解いているかを固定する。
     */
    @Test
    @DisplayName("並(50%)の良薬は下限クランプに飲まれず、張り付きの手前まで蓄積を削る")
    void lesserTonicIsNotSwallowedByTheFloor() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 5_000_000.0);

        DailyExpDiminishing.Status before = daily.status(settings(), player, "MINING");
        assertEquals(0.5, before.multiplier(), 1e-9, "前提: 下限へ張り付いていること");

        int changed = daily.relieve(settings(), player, ExpCleanseTonic.LESSER.targetMultiplier());

        assertEquals(1, changed, "並の良薬が何もしていない（下限クランプに飲まれた）");
        DailyExpDiminishing.Status after = daily.status(settings(), player, "MINING");
        assertTrue(after.accumulated() < 700_000.0,
                "蓄積が削れていない: " + after.accumulated());
        assertTrue(after.multiplier() > before.multiplier(),
                "下限へ張り付いた状態から1段でも上がっていない: " + after.multiplier());
        // 「等倍まで」の見積りは強制解除(24時間)との早い方なので、そこを見ても差が出ない。
        // 並の良薬の価値は【指数減衰そのものが短くなること】なので、期限を含まない生の見積りで測る。
        assertTrue(DailyExpDiminishing.millisUntilFullRecovery(settings(), after.accumulated())
                        < DailyExpDiminishing.millisUntilFullRecovery(settings(), before.accumulated()),
                "自然回復までの時間が縮んでいない（並の良薬の唯一の価値）");
    }

    @Test
    @DisplayName("極(90%)の良薬は取得量を90%以上へ戻す")
    void supremeTonicPullsTheRateBackToNinety() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "WOODCUTTING", 1_000_000.0);
        assertEquals(0.5, daily.status(settings(), player, "WOODCUTTING").multiplier(), 1e-9);

        daily.relieve(settings(), player, ExpCleanseTonic.SUPREME.targetMultiplier());

        assertTrue(daily.status(settings(), player, "WOODCUTTING").multiplier() >= 0.9,
                "極の良薬なのに90%へ戻っていない");
    }

    @Test
    @DisplayName("効果は全スキル一括（ユーザー選択 2026-08-24）")
    void everySkillIsRelieved() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 1_000_000.0);
        daily.consume(settings(), player, "FARMING", 1_000_000.0);
        daily.consume(settings(), player, "FISHING", 1_000_000.0);

        assertEquals(3, daily.relieve(settings(), player, ExpCleanseTonic.GREATER.targetMultiplier()));
        for (String skill : new String[] {"MINING", "FARMING", "FISHING"}) {
            assertTrue(daily.status(settings(), player, skill).multiplier() >= 0.75, skill);
        }
    }

    @Test
    @DisplayName("目減りしていない人が飲んでも0件（呼び出し側はこれを見て消費を止める）")
    void nothingHappensWhenAlreadyAboveTheTarget() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 1_000.0);

        assertEquals(0, daily.relieve(settings(), player, ExpCleanseTonic.SUPREME.targetMultiplier()));
    }

    @Test
    @DisplayName("逓減が無効なら何もしない")
    void disabledSettingsChangeNothing() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        assertEquals(0, daily.relieve(DailyExpDiminishing.Settings.DISABLED, player, 0.9));
    }

    /**
     * <b>DBまで削らないと良薬は再ログインで無かったことになる。</b>
     * {@code DailyExpWindowStore#save} は「メモリと保存済みの<b>大きい方</b>」を残す
     *（サーバ移動で蓄積を後退させないための仕様）ので、メモリだけ削って保存すると
     * 保存済みの大きな値がそのまま勝つ。
     */
    @Test
    @DisplayName("良薬の効果は再ログインをまたいで残る（DB側も切り下げる）")
    void reliefSurvivesRelogin() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 1_000_000.0);
        persistence.save(player);

        daily.relieve(settings(), player, ExpCleanseTonic.SUPREME.targetMultiplier());
        persistence.capStored(player, ExpCleanseTonic.SUPREME.targetMultiplier());

        persistence.saveAndForget(player);
        persistence.load(player);

        assertTrue(daily.status(settings(), player, "MINING").multiplier() >= 0.9,
                "入り直しただけで良薬の効果が消えた（DB側の切り下げが効いていない）");
    }

    @Test
    @DisplayName("DB側の切り下げは目標より少ない行には触らない")
    void capStoredLeavesSmallRowsAlone() throws SQLException {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 50_000.0);
        persistence.save(player);

        assertEquals(0, store.capAmounts(player,
                DailyExpDiminishing.maxAmountFor(settings(), 0.9), settings().windowMillis()));
        assertEquals(50_000.0, store.load(player).get(0).amount(), 1.0);
    }
}
