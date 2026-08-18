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
        /**
         * 直前の付与に実際に掛かった倍率（2026-08-18、通知用）。
         *
         * <p><b>「減衰だけ適用した今の値」と比べてはいけない</b> ── 付与は蓄積を増やす方向にしか
         * 動かないので、その比較では倍率が上がることが構造的にあり得ず「戻りました」が永久に出ない。
         * プレイヤーにとっての意味は「前回もらえた率」と「今回もらえた率」の差なので、
         * 前回返した倍率そのものを覚える。
         */
        private double lastMultiplier = 1.0;

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
        return consumeDetailed(settings, playerId, skillId, amount).multiplier();
    }

    /**
     * {@link #consume} と同じことをして、<b>この付与の前後で倍率が動いたか</b>も返す。
     *
     * <p>段が落ちた／戻った瞬間をプレイヤーへ通知するために要る（2026-08-18）。
     * 「今の倍率」だけでは前回との差が取れず、通知側が自前で前回値を覚えると
     * <b>付与とスレッドが違うので取りこぼす</b>（EXP付与は非同期タスクから走る）。
     * 差分の判定は蓄積を進めるのと同じ {@code synchronized} ブロックの中で確定させる。
     *
     * <p>比較相手は<b>直前の付与に掛かった倍率</b>。「減衰だけ適用した今の値」と比べる実装にすると、
     * 付与は蓄積を増やす方向にしか動かないので倍率が上がることが構造的に起こらず、
     * <b>「戻りました」が永久に出ない</b>（実際にその実装で書いてテストに落とされた）。
     */
    public Applied consumeDetailed(Settings settings, UUID playerId, String skillId, double amount) {
        if (settings == null || !settings.enabled() || playerId == null || skillId == null) {
            return Applied.UNCHANGED;
        }
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return Applied.UNCHANGED;
        }
        if (settings.exemptSkills().contains(skillId)) {
            return Applied.UNCHANGED;
        }
        long now = clock.getAsLong();
        Map<String, Window> perSkill = windows.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>());
        Window window = perSkill.computeIfAbsent(skillId, id -> new Window(now));
        double previous;
        double multiplier;
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
            multiplier = multiplierFor(settings, accumulated);
            previous = window.lastMultiplier;
            window.lastMultiplier = multiplier;
        }
        return new Applied(multiplier, previous, accumulated);
    }

    /**
     * 1回の付与に対して実際に掛かった倍率と、その直前の倍率。
     *
     * @param multiplier         この付与に掛かった倍率
     * @param previousMultiplier <b>直前の付与</b>に掛かった倍率（初回は 1.0）
     * @param accumulated        付与後の蓄積量
     */
    public record Applied(double multiplier, double previousMultiplier, double accumulated) {

        /** 逓減が働いていない状態（無効・免除・付与量0）。 */
        public static final Applied UNCHANGED = new Applied(1.0, 1.0, 0.0);

        /** 段が落ちた（取得量が減った）。 */
        public boolean worsened() {
            return multiplier < previousMultiplier;
        }

        /** 段が戻った（取得量が増えた）。 */
        public boolean improved() {
            return multiplier > previousMultiplier;
        }
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

    /**
     * 倍率が<b>実際に1段よくなる</b>まで、あと何ミリ秒その スキルを休めばよいかの目安。
     * 既に等倍なら {@code -1}。
     *
     * <p><b>「1段減る」ではなく「倍率が変わる」で数える</b>のが肝。下限({@link Settings#floor})に
     * 張り付いている領域では段が1つ減っても倍率は動かないので、素朴に
     * {@code (段数-1)×perAmount} を目標にすると「あと少しで回復」と出したのに何も変わらない、
     * という嘘の表示になる。
     */
    public static double millisUntilNextImprovement(Settings settings, double accumulated) {
        if (settings == null || !settings.enabled() || !Double.isFinite(accumulated)) {
            return -1.0;
        }
        double current = multiplierFor(settings, accumulated);
        if (current >= 1.0) {
            return -1.0;
        }
        long steps = (long) Math.floor(accumulated / settings.perAmount());
        long target = -1L;
        // 下限クランプで潰れている段を飛ばして、初めて倍率が上がる段を探す。
        // 走査は「クランプが解ける段」で必ず止まるので、上限は暴走よけの保険。
        for (long candidate = steps - 1L; candidate >= 0L && steps - candidate <= 4096L; candidate--) {
            if (Math.pow(settings.decayPerAmount(), candidate) > current + 1.0e-9) {
                target = candidate;
                break;
            }
        }
        if (target < 0L) {
            return -1.0;
        }
        return millisUntilAmount(settings, accumulated, (target + 1L) * settings.perAmount());
    }

    /** 完全に等倍へ戻るまでの目安ミリ秒。既に等倍なら {@code -1}。 */
    public static double millisUntilFullRecovery(Settings settings, double accumulated) {
        if (settings == null || !settings.enabled()
                || multiplierFor(settings, accumulated) >= 1.0) {
            return -1.0;
        }
        return millisUntilAmount(settings, accumulated, settings.perAmount());
    }

    /**
     * 蓄積が {@code target} まで落ちるのに掛かる時間。指数減衰
     * {@code A(t) = A × exp(-t / 窓)} を t について解いた {@code t = 窓 × ln(A / target)}。
     *
     * <p><b>この見積りは「そのスキルでEXPを稼がずにオンラインでいる」前提</b>。
     * 蓄積は永続化しておらず退出時に捨てられるので、再ログインした場合はここで出した時間を
     * 待たずにリセットされる（＝表示より早く戻る方向にしか外れない）。
     */
    private static double millisUntilAmount(Settings settings, double accumulated, double target) {
        if (target <= 0.0 || accumulated <= target) {
            return 0.0;
        }
        return settings.windowMillis() * Math.log(accumulated / target);
    }

    /**
     * 表示用のスナップショット。GUI とチャットで別々に計算すると必ず食い違うので、
     * 出す数字は全部ここから引く。
     *
     * @param multiplier           現在の倍率
     * @param accumulated          現在の蓄積量
     * @param expUntilNextStep     次に1段落ちるまでの残りEXP（下限に張り付いていれば {@code -1}）
     * @param millisUntilImproved  倍率が1段よくなるまでの目安ミリ秒（等倍なら {@code -1}）
     * @param millisUntilFull      等倍へ戻るまでの目安ミリ秒（等倍なら {@code -1}）
     */
    public record Status(double multiplier, double accumulated, double expUntilNextStep,
                         double millisUntilImproved, double millisUntilFull) {

        /** 逓減が掛かっていない（等倍）。 */
        public boolean atFullRate() {
            return multiplier >= 1.0;
        }
    }

    /** 蓄積量から表示用スナップショットを組む純関数。 */
    public static Status statusOf(Settings settings, double accumulated) {
        double multiplier = multiplierFor(settings, accumulated);
        return new Status(multiplier, Math.max(0.0, accumulated),
                untilNextStep(settings, accumulated),
                millisUntilNextImprovement(settings, accumulated),
                millisUntilFullRecovery(settings, accumulated));
    }

    /** そのプレイヤー・スキルの現在の表示用スナップショット（状態は進めない）。 */
    public Status status(Settings settings, UUID playerId, String skillId) {
        return statusOf(settings, accumulated(settings, playerId, skillId));
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
