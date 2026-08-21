package com.trinityforge.progression;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
 * <p><b>永続化する</b>（2026-08-18 ユーザー指示「ログアウトで蓄積が全部消える＝これは直さないとダメ」）。
 * このクラス自身はメモリしか持たないが、{@link #snapshot(UUID)} と
 * {@link #restore(Settings, UUID, java.util.Collection)} を通じて
 * {@code DailyExpWindowStore}（共有 SQLite）へ出し入れする。
 * <b>永続化しないと機構ごと無効になる</b> ── 退出で蓄積が消えるなら、
 * 逓減が効き始めた瞬間に再ログインするだけで等倍に戻せてしまい、
 * 「1日の稼ぎ総量を薄める」という目的が1ミリも達成されない。
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
        /**
         * <b>逓減が発動した時刻</b>（倍率が初めて 1.0 を下回った瞬間）。等倍なら 0
         *（2026-08-19 / W-154、ユーザー指示「一度かかったら24時間で強制解除して100%へ戻す」）。
         *
         * <p><b>なぜ「最終更新から24時間」ではないのか</b>: ユーザーの選択は
         * 「<b>逓減が発動した時刻から</b>24時間」。稼ぎ続けている間も時計は進み、
         * 24時間経てば必ず等倍へ戻る。指数減衰だけだと 24 時間放置しても
         * {@code e^-1 ≒ 37%} が残る（＝一度下がると戻り切らない）ので、
         * この期限が無いと「ロックが解けない」という体感になる。
         */
        private long lockedAtMillis;
        /**
         * 逓減が掛かっているか。<b>{@code lockedAtMillis > 0} で代用してはいけない</b> ——
         * 時計を 0 から進めるテストでは「発動時刻 0」が「未発動」と区別できず、期限が永久に来ない
         *（実際にそれで落ちた）。実運用の {@code currentTimeMillis} が 0 を返さないことに
         * 依存した書き方をしない。
         */
        private boolean locked;

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
            // 24時間経過での強制解除を先に見る（W-154）。ここで解除しないと、期限切れの蓄積に
            // 今回の付与を足した値で倍率を決めてしまい、解除された瞬間にまた下がる。
            releaseIfExpired(settings, window, now);
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
            stampLock(window, multiplier, now);
        }
        return new Applied(multiplier, previous, accumulated);
    }

    /**
     * 期限（{@link Settings#lockReleaseMillis}）を過ぎた逓減を<b>完全に解除</b>する
     * （2026-08-19 / W-154）。呼び出し側は {@code window} を保持していること。
     *
     * <p>解除は「蓄積をゼロに戻す」であって「倍率だけ 1.0 に見せる」ではない。倍率だけ戻すと、
     * 次の付与で蓄積がそのまま効いて即座に下がり直し、プレイヤーからは<b>解除されていないのと同じ</b>に見える。
     *
     * @return 解除したなら true
     */
    private static boolean releaseIfExpired(Settings settings, Window window, long now) {
        if (settings == null || settings.lockReleaseMillis() <= 0.0 || !window.locked) {
            return false;
        }
        if (now - window.lockedAtMillis < settings.lockReleaseMillis()) {
            return false;
        }
        window.amount = 0.0;
        window.lastMultiplier = 1.0;
        window.locked = false;
        window.lockedAtMillis = 0L;
        window.updatedAtMillis = now;
        return true;
    }

    /**
     * 逓減の発動時刻を打つ／解除する（2026-08-19 / W-154）。
     *
     * <p>等倍へ戻ったら時刻を落とす —— 落とさないと、自然回復で等倍に戻ってからまた下がったときに
     * <b>前回の発動時刻のまま</b>期限を数えることになり、下がった直後に解除される。
     */
    private static void stampLock(Window window, double multiplier, long now) {
        if (multiplier < 1.0) {
            if (!window.locked) {
                window.locked = true;
                window.lockedAtMillis = now;
            }
        } else {
            window.locked = false;
            window.lockedAtMillis = 0L;
        }
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
     * <p><b>この見積りは「そのスキルでEXPを稼がない」前提</b>。蓄積は退出後も永続化され
     * オフライン時間ぶんも同じ式で減衰するので、ログアウトして待った場合も同じ時間で戻る
     * （2026-08-18 に永続化するまでは「再ログインで即リセット」だった）。
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

    /**
     * 蓄積量から表示用スナップショットを組む純関数（強制解除の期限を考えない版）。
     *
     * <p><b>プレイヤーへ出す数字にはこちらを使わない。</b> 期限を知らないので
     * 「等倍まで」が実際より長く出る（理由は {@link #statusOf(Settings, double, double)}）。
     * 蓄積量だけから決まる部分を確かめたい試験や、期限を持たない呼び出し用。
     */
    public static Status statusOf(Settings settings, double accumulated) {
        return statusOf(settings, accumulated, -1.0);
    }

    /**
     * 蓄積量と<b>強制解除までの残り時間</b>から表示用スナップショットを組む純関数。
     *
     * <p><b>なぜ残り時間を渡すのか</b>（2026-08-21 実サーバ報告「24時間経験値減衰の仕様に関して
     * 正しく時間経過でリセットされているが表示が前のデータのまま？なのか、リセットまで24時間を
     * 超えているらしい」）: 回復の見積り（{@link #millisUntilFullRecovery}）は指数減衰だけを解いた値で、
     * <b>W-154 の「発動から24時間で強制解除」を1つも見ていなかった</b>。指数減衰は
     * {@code 窓 × ln(蓄積 / perAmount)} なので、出荷設定（窓24h・perAmount 10万）では
     * 蓄積30万で26時間、100万で55時間と<b>平然と24時間を超える数字が出る</b> ──
     * 実際には遅くとも発動から24時間で等倍へ戻るのに、画面には「等倍まで55時間」と出ていた。
     * 減衰そのものは正しく動いていたので、<b>ズレていたのは表示だけ</b>。
     *
     * <p>期限が有効なら「指数減衰での見積り」と「解除までの残り」の<b>早い方</b>を採る。
     * 倍率が下がっているのに指数側が答えを出せない場合（下限に張り付いていて段が動かない等）は
     * 解除までの残りをそのまま使う ── 期限があるかぎり必ずその時刻には戻るため。
     *
     * @param millisUntilForcedRelease 強制解除までの残りミリ秒。負なら期限なし（従来どおり）
     */
    public static Status statusOf(Settings settings, double accumulated, double millisUntilForcedRelease) {
        double multiplier = multiplierFor(settings, accumulated);
        double untilImproved = millisUntilNextImprovement(settings, accumulated);
        double untilFull = millisUntilFullRecovery(settings, accumulated);
        if (multiplier < 1.0 && millisUntilForcedRelease >= 0.0) {
            untilImproved = soonerOf(untilImproved, millisUntilForcedRelease);
            untilFull = soonerOf(untilFull, millisUntilForcedRelease);
        }
        return new Status(multiplier, Math.max(0.0, accumulated),
                untilNextStep(settings, accumulated), untilImproved, untilFull);
    }

    /** 見積り {@code estimate}（負 = 出せない）と期限 {@code deadline} の早い方。 */
    private static double soonerOf(double estimate, double deadline) {
        return estimate < 0.0 ? deadline : Math.min(estimate, deadline);
    }

    /**
     * そのプレイヤー・スキルの現在の表示用スナップショット（状態は進めない）。
     *
     * <p>蓄積量と強制解除までの残りを<b>同じロックの中で</b>読む。別々に読むと、
     * 間に付与が挟まったときに「解除間近なのに蓄積は解除後の値」のような、実在しない組み合わせが出る。
     */
    public Status status(Settings settings, UUID playerId, String skillId) {
        Window window = windowOf(playerId, skillId);
        if (settings == null || window == null) {
            return statusOf(settings, 0.0);
        }
        long now = clock.getAsLong();
        synchronized (window) {
            if (isExpired(settings, window, now)) {
                return statusOf(settings, 0.0);
            }
            return statusOf(settings, decayedAmount(settings, window, now),
                    millisUntilForcedRelease(settings, window, now));
        }
    }

    /** 現在の蓄積量（減衰を適用した値。状態は進めない）。表示・デバッグ用。 */
    public double accumulated(Settings settings, UUID playerId, String skillId) {
        Window window = windowOf(playerId, skillId);
        if (settings == null || window == null) {
            return 0.0;
        }
        long now = clock.getAsLong();
        synchronized (window) {
            // 期限切れ（W-154）は「もう蓄積は無い」と読む。ここで見ないと、表示だけが
            // 解除前の倍率を出し続け、実際の付与（consumeDetailed 側で解除される）と食い違う。
            return isExpired(settings, window, now) ? 0.0 : decayedAmount(settings, window, now);
        }
    }

    /** 追跡中の {@link Window}。無ければ {@code null}。 */
    private Window windowOf(UUID playerId, String skillId) {
        if (playerId == null || skillId == null) {
            return null;
        }
        Map<String, Window> perSkill = windows.get(playerId);
        return perSkill == null ? null : perSkill.get(skillId);
    }

    /** 強制解除（W-154）の期限を過ぎているか。{@code window} のロックを持って呼ぶこと。 */
    private static boolean isExpired(Settings settings, Window window, long now) {
        return settings.lockReleaseMillis() > 0.0 && window.locked
                && now - window.lockedAtMillis >= settings.lockReleaseMillis();
    }

    /**
     * 強制解除までの残りミリ秒。期限が無い／まだ発動していないなら {@code -1}。
     * {@code window} のロックを持って呼ぶこと。
     */
    private static double millisUntilForcedRelease(Settings settings, Window window, long now) {
        if (settings.lockReleaseMillis() <= 0.0 || !window.locked) {
            return -1.0;
        }
        return Math.max(0.0, settings.lockReleaseMillis() - (double) (now - window.lockedAtMillis));
    }

    /** 最終更新から {@code now} までの指数減衰を適用した蓄積量。{@code window} のロックを持って呼ぶこと。 */
    private static double decayedAmount(Settings settings, Window window, long now) {
        long elapsed = Math.max(0L, now - window.updatedAtMillis);
        if (elapsed <= 0L || window.amount <= 0.0) {
            return window.amount;
        }
        return window.amount * Math.exp(-((double) elapsed) / settings.windowMillis());
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

    /** 追跡中のプレイヤー（停止時の一括保存で回すため）。 */
    public Set<UUID> trackedPlayerIds() {
        return Set.copyOf(windows.keySet());
    }

    /**
     * 永続化する1行ぶん。<b>減衰を適用しない生の値</b>を持つ。
     *
     * <p>保存時点で「今まで」減衰させてしまうと、保存から復元までのオフライン時間が
     * 二重に効くか、逆に一切効かないかのどちらかになる。生の {@code (量, その時刻)} を
     * そのまま持ち回れば、復元側が「保存時刻→現在」を1回だけ掛ければ済む。
     *
     * @param skillId         正規化済みスキルID
     * @param amount          減衰前の蓄積量
     * @param updatedAtMillis その量が有効だった時刻
     */
    public record WindowSnapshot(String skillId, double amount, long updatedAtMillis,
                                 long lockedAtMillis) {

        /**
         * 逓減の発動時刻を持たない旧形式（2026-08-19 / W-154 より前）。
         * <b>0 = 未発動</b>として扱う。既存の呼び出し・テストを壊さないために残してある。
         */
        public WindowSnapshot(String skillId, double amount, long updatedAtMillis) {
            this(skillId, amount, updatedAtMillis, 0L);
        }
    }

    /** そのプレイヤーの全スキルぶんの保存用スナップショット（状態は進めない）。 */
    public List<WindowSnapshot> snapshot(UUID playerId) {
        if (playerId == null) {
            return List.of();
        }
        Map<String, Window> perSkill = windows.get(playerId);
        if (perSkill == null || perSkill.isEmpty()) {
            return List.of();
        }
        List<WindowSnapshot> out = new ArrayList<>(perSkill.size());
        for (Map.Entry<String, Window> e : perSkill.entrySet()) {
            Window window = e.getValue();
            synchronized (window) {
                if (window.amount > 0.0) {
                    // 未発動は 0 で表す(実運用の currentTimeMillis は 0 を返さないので衝突しない)。
                    out.add(new WindowSnapshot(e.getKey(), window.amount, window.updatedAtMillis,
                            window.locked ? window.lockedAtMillis : 0L));
                }
            }
        }
        return out;
    }

    /**
     * 保存しておいた蓄積をメモリへ戻す。<b>上書きではなくマージ</b>。
     *
     * <p>読み込んだ量は保存時刻から現在まで、既にメモリにある量は最終更新から現在まで、
     * それぞれ指数減衰させてから足す。上書きにすると、ログイン直後の1回目の付与が先に
     * 走っていた場合（非同期ロードなので普通に起こる）にその分が消えるし、
     * 逆に読み込みを捨てると永続化した意味が無くなる。
     *
     * <p>{@code lastMultiplier} も復元後の倍率へ合わせる。1.0 のままにすると、
     * ログイン後の最初の付与で「取得量が下がりました」という偽の通知が必ず出る
     * （実際には下がっておらず、ログアウト前から下がったままなだけ）。
     */
    public void restore(Settings settings, UUID playerId, Collection<WindowSnapshot> entries) {
        if (settings == null || playerId == null || entries == null || entries.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        for (WindowSnapshot entry : entries) {
            if (entry == null || entry.skillId() == null || entry.skillId().isBlank()) {
                continue;
            }
            if (!Double.isFinite(entry.amount()) || entry.amount() <= 0.0) {
                continue;
            }
            // 期限切れ（W-154）の行は復元しない。復元してしまうと、ログインし直すたびに
            // 解除済みのロックが蘇る（＝期限が事実上無くなる）。
            if (settings.lockReleaseMillis() > 0.0 && entry.lockedAtMillis() > 0L
                    && now - entry.lockedAtMillis() >= settings.lockReleaseMillis()) {
                continue;
            }
            long offline = Math.max(0L, now - entry.updatedAtMillis());
            double carried = entry.amount()
                    * Math.exp(-((double) offline) / settings.windowMillis());
            if (!Double.isFinite(carried) || carried <= 0.0) {
                continue;
            }
            Map<String, Window> perSkill =
                    windows.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>());
            Window window = perSkill.computeIfAbsent(entry.skillId(), id -> new Window(now));
            synchronized (window) {
                long elapsed = Math.max(0L, now - window.updatedAtMillis);
                if (elapsed > 0L && window.amount > 0.0) {
                    window.amount = window.amount
                            * Math.exp(-((double) elapsed) / settings.windowMillis());
                }
                window.updatedAtMillis = now;
                window.amount += carried;
                window.lastMultiplier = multiplierFor(settings, window.amount);
                // 発動時刻は「早い方」を採る（W-154）。遅い方を採ると、サーバ移動や再ログインの
                // たびに期限が延びて、24時間経っても解除されない。
                if (entry.lockedAtMillis() > 0L
                        && (!window.locked || entry.lockedAtMillis() < window.lockedAtMillis)) {
                    window.locked = true;
                    window.lockedAtMillis = entry.lockedAtMillis();
                }
                stampLock(window, window.lastMultiplier, now);
            }
        }
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
     * @param lockReleaseMillis 逓減が発動してからこの時間が経ったら<b>蓄積ごとゼロに戻して等倍へ戻す</b>
     *                          （2026-08-19 / W-154、既定 24時間）。0 なら期限なし（従来どおり指数減衰だけで戻る）
     */
    public record Settings(boolean enabled, double windowMillis, double perAmount,
                           double decayPerAmount, double floor, Set<String> exemptSkills,
                           double lockReleaseMillis) {

        public Settings {
            windowMillis = Math.max(1.0, windowMillis);
            perAmount = Math.max(1.0, perAmount);
            decayPerAmount = Math.max(0.0, Math.min(1.0, decayPerAmount));
            floor = Math.max(0.0, Math.min(1.0, floor));
            exemptSkills = exemptSkills == null ? Set.of() : Set.copyOf(exemptSkills);
            lockReleaseMillis = Double.isFinite(lockReleaseMillis) && lockReleaseMillis > 0.0
                    ? lockReleaseMillis : 0.0;
        }

        /**
         * 強制解除を持たない旧形式（2026-08-19 / W-154 より前）。既存の呼び出し・テストを
         * 壊さないために残してある。{@code lockReleaseMillis = 0} ＝<b>期限なし</b>
         *（指数減衰だけで戻る従来の挙動）。
         */
        public Settings(boolean enabled, double windowMillis, double perAmount,
                        double decayPerAmount, double floor, Set<String> exemptSkills) {
            this(enabled, windowMillis, perAmount, decayPerAmount, floor, exemptSkills, 0.0);
        }

        /** 逓減なし。 */
        public static final Settings DISABLED =
                new Settings(false, 86_400_000.0, 1.0, 1.0, 1.0, Set.of(), 0.0);
    }
}
