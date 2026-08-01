package com.trinityforge.progression;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 「直近24時間にそのスキルで稼いだ量」に応じて EXP 取得量を薄めていく逓減
 * （2026-07-31 ユーザー確定: 格差の吸収策として「既存の24時間EXPによって取得量が軽減されていく設定」）。
 *
 * <p><b>なぜ作ったか</b>: この機構は「既にある」と思われていたが、実際に存在したのは
 * {@code spot-diminishing}（半径16ブロック・直近300秒の同一地点狩り抑制）だけで、
 * <b>1日単位で稼ぎ総量を薄める仕組みは1つも無かった</b>。1日12時間プレイする層と
 * 週末だけの層の差を縮めるには、地点ではなく総量に効くものが要る。
 *
 * <p><b>アルゴリズム: 指数移動窓（バケットではない）</b>。プレイヤー×スキルごとに
 * {@code (最終更新時刻, 蓄積量)} の2値だけを持ち、付与のたびに
 * <pre>蓄積 = 蓄積 × exp(-経過時間 / 窓) + 今回の付与量</pre>
 * とする。これで
 * <ul>
 *   <li><b>O(1)</b>: 既存の {@link LocationExpDiminishing} のように無制限の履歴を走査しない
 *       （あちらは付与ごとに {@code ArrayDeque} を線形走査する）。</li>
 *   <li><b>境界の段差が無い</b>: 「日付が変わった瞬間に全部リセット」だと 23:59 に稼ぎを溜めて
 *       00:00 から全開、という遊び方が最適解になってしまう。指数減衰なら常に滑らかに戻る。</li>
 *   <li><b>メモリが有界</b>: プレイヤー数 × スキル数のエントリしか増えず、
 *       {@link #forget(UUID)} で退出時に落とせる。</li>
 * </ul>
 *
 * <p><b>倍率</b>（2026-08-01 ユーザー確定の仕様）: 時間窓での総獲得量が {@code perAmount} に
 * 達するたびに1段落ちる。段数 {@code n = floor(蓄積 / perAmount)} に対して
 * {@code decayPerAmount^n} を掛け、{@code floor} で下限を打つ。
 * <b>段数は切り捨て（離散）</b>にしてある ── 連続に薄めると「気づかないうちにじわじわ減っている」
 * だけで、あと何EXPで落ちるのか・何をすれば戻るのかがプレイヤーに伝わらないため。
 *
 * <p><b>永続化しない</b>のは意図的。サーバ再起動で蓄積が消えるが、プレイヤーは再起動を選べないので
 * 悪用経路にならず、DB スキーマを増やさずに済む。
 */
public final class DailyExpDiminishing {

    /** プレイヤー×スキルの蓄積状態。{@code amount} は指数減衰済みの「実効的な直近獲得量」。 */
    private static final class Window {
        private long updatedAtMillis;
        private double amount;

        Window(long nowMillis) {
            this.updatedAtMillis = nowMillis;
        }
    }

    private final Map<UUID, Map<String, Window>> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    /** 実運用用（{@link System#currentTimeMillis()}）。 */
    public DailyExpDiminishing() {
        this(System::currentTimeMillis);
    }

    /** テスト用に時刻を差し替えられる構築子。 */
    public DailyExpDiminishing(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * このEXP付与に掛ける倍率を返し、<b>同時に蓄積を進める</b>（読み取りと更新が一体なので、
     * 1回の付与につき1回だけ呼ぶこと）。
     *
     * @param settings 設定。{@code null} や無効なら常に 1.0
     * @param playerId 付与対象
     * @param skillId  正規化済みスキルID
     * @param amount   逓減前の付与量（これも蓄積に加える）
     */
    public double consume(Settings settings, UUID playerId, String skillId, double amount) {
        if (settings == null || !settings.enabled() || playerId == null || skillId == null) {
            return 1.0;
        }
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return 1.0;
        }
        if (settings.exemptSkills().contains(skillId)) {
            return 1.0;
        }
        long now = clock.getAsLong();
        Map<String, Window> perSkill = windows.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>());
        Window window = perSkill.computeIfAbsent(skillId, id -> new Window(now));
        double accumulated;
        synchronized (window) {
            long elapsed = Math.max(0L, now - window.updatedAtMillis);
            if (elapsed > 0L && window.amount > 0.0) {
                window.amount = window.amount * Math.exp(-((double) elapsed) / settings.windowMillis());
            }
            window.updatedAtMillis = now;
            // 「この付与を含めた」蓄積で倍率を決める。含めないと threshold ぴったりの一撃が
            // 常に等倍で通り、大量EXPを1回で受け取る経路（ボス撃破など）が逓減をすり抜ける。
            window.amount += amount;
            accumulated = window.amount;
        }
        return multiplierFor(settings, accumulated);
    }

    /**
     * 蓄積量から倍率を決める純関数（テストと表示の両方から使う）。
     *
     * <p><b>2026-08-01 ユーザー確定の仕様変更</b>: 旧実装は「{@code threshold} までは等倍、
     * 超過分を {@code step} で割った<b>実数</b>段数で連続的に薄める」だったが、
     * <b>「時間窓での総獲得量が {@code perAmount} に達するたびに、取得量が現在の
     * {@code 1 - decayPerAmount} だけ減る」</b>という<b>離散</b>の刻みへ変更した。
     * 段数を {@link Math#floor} で丸めるので、
     * <pre>倍率 = decayPerAmount ^ floor(蓄積量 / perAmount)</pre>
     * となり、「あと少しで1段落ちる」がプレイヤー側から数えられる（連続だと
     * 「気づかないうちにじわじわ減っている」になり、何をすれば戻るのかが伝わらない）。</p>
     */
    public static double multiplierFor(Settings settings, double accumulated) {
        if (settings == null || !settings.enabled()) {
            return 1.0;
        }
        if (!Double.isFinite(accumulated) || accumulated <= 0.0) {
            return 1.0;
        }
        double steps = Math.floor(accumulated / settings.perAmount());
        if (steps <= 0.0) {
            return 1.0;
        }
        double multiplier = Math.pow(settings.decayPerAmount(), steps);
        if (!Double.isFinite(multiplier)) {
            return settings.floor();
        }
        return Math.max(settings.floor(), Math.min(1.0, multiplier));
    }

    /**
     * 次の刻みまであと何EXPか（表示用）。既に下限へ張り付いているときは {@code -1}。
     * 「あとどれだけ稼ぐと減るのか」をプレイヤーへ出せるようにするための補助。
     */
    public static double untilNextStep(Settings settings, double accumulated) {
        if (settings == null || !settings.enabled() || multiplierFor(settings, accumulated) <= settings.floor()) {
            return -1.0;
        }
        double per = settings.perAmount();
        double into = Math.max(0.0, accumulated) % per;
        return per - into;
    }

    /** 現在の蓄積量（減衰を適用した値。状態は進めない）。表示・デバッグ用。 */
    public double accumulated(Settings settings, UUID playerId, String skillId) {
        if (settings == null || playerId == null || skillId == null) {
            return 0.0;
        }
        Map<String, Window> perSkill = windows.get(playerId);
        if (perSkill == null) {
            return 0.0;
        }
        Window window = perSkill.get(skillId);
        if (window == null) {
            return 0.0;
        }
        long now = clock.getAsLong();
        synchronized (window) {
            long elapsed = Math.max(0L, now - window.updatedAtMillis);
            if (elapsed <= 0L || window.amount <= 0.0) {
                return window.amount;
            }
            return window.amount * Math.exp(-((double) elapsed) / settings.windowMillis());
        }
    }

    /** 退出時などにプレイヤーの状態を捨てる（メモリを有界に保つ）。 */
    public void forget(UUID playerId) {
        if (playerId != null) {
            windows.remove(playerId);
        }
    }

    /** 追跡中のプレイヤー数（テスト用）。 */
    public int trackedPlayers() {
        return windows.size();
    }

    /**
     * {@code stats/skill-exp.yml} の {@code daily-diminishing.*}。
     *
     * @param enabled         false なら常に 1.0（既定 false = 導入しても config を書くまで挙動が変わらない）
     * @param windowMillis    指数減衰の時定数。24時間なら 86,400,000
     * @param perAmount       この量を稼ぐたびに1段減る（時間窓での総獲得量で数える）
     * @param decayPerAmount  1段あたりの倍率（0.8 なら1段ごとに現在の80%＝20%減る）
     * @param floor           倍率の下限（0 にすると完全に稼げなくなるので必ず 0 より大きくする）
     * @param exemptSkills    逓減しないスキルID
     */
    public record Settings(boolean enabled, double windowMillis, double perAmount,
                           double decayPerAmount, double floor, Set<String> exemptSkills) {

        public Settings {
            windowMillis = Math.max(1.0, windowMillis);
            perAmount = Math.max(1.0, perAmount);
            decayPerAmount = Math.max(0.0, Math.min(1.0, decayPerAmount));
            floor = Math.max(0.0, Math.min(1.0, floor));
            exemptSkills = exemptSkills == null ? Set.of() : Set.copyOf(exemptSkills);
        }

        /** 逓減なし。 */
        public static final Settings DISABLED =
                new Settings(false, 86_400_000.0, 1.0, 1.0, 1.0, Set.of());
    }
}
