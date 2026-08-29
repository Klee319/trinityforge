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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「EXP解呪の良薬」（{@link ExpCleanseTonic}）が日次逓減を動かすことを固定する
 * （2026-08-29 仕様差し替え: 並10%+70%上限 / 上15% / 極2時間無効化）。
 *
 * <p>設定は<b>出荷値と同じ</b>（{@code stats/skill-exp.yml}: 窓24時間 / 10万EXPごとに1段 /
 * 1段 0.9倍 / 下限 0.5）。離散段では 70% は段の間に落ちるので、並の上限は
 * 70% を超えない最良段（0.9^4 = 65.61%）になる。
 */
class ExpCleanseTonicReliefTest {

    private static final double HOUR = 3_600_000.0;
    private static final double PER = 100_000.0;
    private static final long TWO_HOURS = 2L * 60L * 60L * 1000L;

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
    @DisplayName("良薬3種は減衰量10%/15%と2時間無効化（数字の向きを取り違えない）")
    void tonicsCutThePenaltyNotAFixedFloor() {
        assertEquals(0.10, ExpCleanseTonic.LESSER.decayReduceFraction(), 1e-9);
        assertEquals(0.70, ExpCleanseTonic.LESSER.multiplierCeiling(), 1e-9);
        assertFalse(ExpCleanseTonic.LESSER.grantsImmunity());

        assertEquals(0.15, ExpCleanseTonic.GREATER.decayReduceFraction(), 1e-9);
        assertEquals(1.0, ExpCleanseTonic.GREATER.multiplierCeiling(), 1e-9);
        assertFalse(ExpCleanseTonic.GREATER.grantsImmunity());

        assertTrue(ExpCleanseTonic.SUPREME.grantsImmunity());
        assertEquals(TWO_HOURS, ExpCleanseTonic.SUPREME.immunityMillis());
    }

    @Test
    @DisplayName("並は下限張り付きから減衰量を10%削るが、70%は超えない")
    void lesserCutsTenPercentAndStopsAtSeventy() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 5_000_000.0);

        DailyExpDiminishing.Status before = daily.status(settings(), player, "MINING");
        assertEquals(0.5, before.multiplier(), 1e-9, "前提: 下限へ張り付いていること");

        int changed = daily.relieveDecay(settings(), player,
                ExpCleanseTonic.LESSER.decayReduceFraction(),
                ExpCleanseTonic.LESSER.multiplierCeiling());

        assertEquals(1, changed, "並の良薬が何もしていない");
        DailyExpDiminishing.Status after = daily.status(settings(), player, "MINING");
        assertTrue(after.multiplier() > before.multiplier(),
                "下限から1段も上がっていない: " + after.multiplier());
        assertTrue(after.multiplier() <= 0.70 + 1e-9,
                "並なのに70%を超えた: " + after.multiplier());
        assertTrue(after.accumulated() < before.accumulated(),
                "蓄積が削れていない: " + after.accumulated());
    }

    @Test
    @DisplayName("並は既に70%超のスキルには効かない（悪化させない）")
    void lesserDoesNotPullARateAlreadyAboveTheCeiling() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        // 3段 = 0.9^3 = 72.9%。70% を超えているので並は触らない。
        daily.consume(settings(), player, "MINING", 300_000.0);
        double before = daily.status(settings(), player, "MINING").multiplier();
        assertTrue(before > 0.70, "前提: 72.9% 付近であること: " + before);

        assertEquals(0, daily.relieveDecay(settings(), player, 0.10, 0.70));
        assertEquals(before, daily.status(settings(), player, "MINING").multiplier(), 1e-9);
    }

    @Test
    @DisplayName("上は減衰量を15%削り、70%上限は持たない")
    void greaterCutsFifteenPercentWithoutACeiling() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "WOODCUTTING", 300_000.0);
        double before = daily.status(settings(), player, "WOODCUTTING").multiplier();
        assertTrue(before > 0.70, "前提: 70% 超であること: " + before);

        int changed = daily.relieveDecay(settings(), player,
                ExpCleanseTonic.GREATER.decayReduceFraction(),
                ExpCleanseTonic.GREATER.multiplierCeiling());

        assertEquals(1, changed);
        double after = daily.status(settings(), player, "WOODCUTTING").multiplier();
        assertTrue(after > before, "上の良薬なのに倍率が上がっていない: " + after);
        assertTrue(after > 0.70, "上が70%で止まっている: " + after);
    }

    @Test
    @DisplayName("効果は全スキル一括")
    void everySkillIsRelieved() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 1_000_000.0);
        daily.consume(settings(), player, "FARMING", 1_000_000.0);
        daily.consume(settings(), player, "FISHING", 1_000_000.0);

        assertEquals(3, daily.relieveDecay(settings(), player, 0.15, 1.0));
        for (String skill : new String[] {"MINING", "FARMING", "FISHING"}) {
            assertTrue(daily.status(settings(), player, skill).multiplier() > 0.5, skill);
        }
    }

    @Test
    @DisplayName("目減りしていない人が飲んでも0件（呼び出し側はこれを見て消費を止める）")
    void nothingHappensWhenAlreadyAtFullRate() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 1_000.0);

        assertEquals(0, daily.relieveDecay(settings(), player, 0.10, 0.70));
        assertEquals(0, daily.relieveDecay(settings(), player, 0.15, 1.0));
    }

    @Test
    @DisplayName("逓減が無効なら何もしない")
    void disabledSettingsChangeNothing() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        assertEquals(0, daily.relieveDecay(DailyExpDiminishing.Settings.DISABLED, player, 0.15, 1.0));
    }

    @Test
    @DisplayName("極は2時間、取得を等倍にし蓄積を増やさない")
    void supremeDisablesDecayForTwoHoursWithoutAddingToTheWindow() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 1_000_000.0);
        double stored = daily.status(settings(), player, "MINING").accumulated();
        assertEquals(0.5, daily.status(settings(), player, "MINING").multiplier(), 1e-9);

        daily.grantImmunity(player, ExpCleanseTonic.SUPREME.immunityMillis());
        assertTrue(daily.isImmune(player));
        assertEquals(1.0, daily.status(settings(), player, "MINING").multiplier(), 1e-9,
                "無効化中なのに表示が等倍になっていない");

        DailyExpDiminishing.Applied applied =
                daily.consumeDetailed(settings(), player, "MINING", 500_000.0);
        assertEquals(1.0, applied.multiplier(), 1e-9);
        assertEquals(stored, applied.accumulated(), 1.0,
                "無効化中なのに新しい稼ぎが蓄積へ乗った");

        now.addAndGet(TWO_HOURS + 1L);
        assertFalse(daily.isImmune(player));
        assertTrue(daily.status(settings(), player, "MINING").multiplier() < 1.0,
                "期限が切れたら逓減が再開すること");
    }

    @Test
    @DisplayName("並の切り下げは再ログインをまたいで残る（DB側もスキルごとに削る）")
    void reliefSurvivesRelogin() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 1_000_000.0);
        persistence.save(player);
        double before = daily.status(settings(), player, "MINING").accumulated();

        daily.relieveDecay(settings(), player, 0.15, 1.0);
        persistence.persistRelievedAmounts(player);

        persistence.saveAndForget(player);
        persistence.load(player);

        assertTrue(daily.status(settings(), player, "MINING").accumulated() < before - 1.0,
                "入り直しただけで良薬の効果が消えた（DB側の切り下げが効いていない）");
    }

    @Test
    @DisplayName("極の無効化は再ログインをまたいで残る")
    void immunitySurvivesRelogin() {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 1_000_000.0);
        daily.grantImmunity(player, TWO_HOURS);
        persistence.saveAndForget(player);
        assertFalse(daily.isImmune(player));

        persistence.load(player);
        assertTrue(daily.isImmune(player), "入り直しただけで無効化が消えた");
        assertEquals(1.0, daily.status(settings(), player, "MINING").multiplier(), 1e-9);
    }

    @Test
    @DisplayName("運営リセットは蓄積も無効化も消す")
    void resetClearsWindowsAndImmunity() throws SQLException {
        DailyExpDiminishing daily = new DailyExpDiminishing(now::get);
        DailyExpWindowPersistence persistence = persistence(daily);
        UUID player = UUID.randomUUID();
        daily.consume(settings(), player, "MINING", 1_000_000.0);
        daily.grantImmunity(player, TWO_HOURS);
        persistence.save(player);

        persistence.resetPlayer(player);

        assertFalse(daily.isImmune(player));
        assertEquals(1.0, daily.status(settings(), player, "MINING").multiplier(), 1e-9);
        assertTrue(store.load(player).isEmpty());
        assertTrue(store.loadImmunity(player).isEmpty());
    }
}
