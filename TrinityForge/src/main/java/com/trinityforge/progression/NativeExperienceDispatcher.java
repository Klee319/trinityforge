package com.trinityforge.progression;

import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coalesces high-frequency gameplay EXP on an async one-second cadence. Administrative mutations
 * still use {@link NativeProgressionService} directly. Shutdown drains pending EXP synchronously.
 */
public final class NativeExperienceDispatcher implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NativeExperienceDispatcher.class.getName());
    private static final int MAX_TRANSIENT_RETRIES = 3;
    /**
     * {@link #collectBatch()} が同じキーで連続してCAS失敗した回数がここへ達したら1回だけ警告する
     * (W-314 タスク1)。CAS失敗そのものはデータ消失ではなく「次回drainへ1秒繰り越し」なので閾値以下は
     * 完全に正常。しかし同一キーへの grant が drain の収集ウィンドウ(数十マイクロ秒)より高頻度で
     * 続けて割り込むと、収集が延々と先送りされる(消失ではなく無期限の遅延)ため、
     * その異常を観測できるようにする。
     */
    private static final int RACE_MISS_WARN_THRESHOLD = 5;

    /**
     * <p><b>W-314 タスク1: drain中のgrant取りこぼし対策。</b>単一の {@link ConcurrentHashMap} を
     * ずっと使い続け、{@link #grant} は常にこのインスタンスへ直接 {@code merge} する。
     * 回収({@link #collectBatch()})は「参照を丸ごと差し替える」のではなく
     * <b>キーを1つずつ {@code remove(key, value)} で確認しながら剥がす</b>方式にした
     * (設計候補 (a)/(b)/(c) のうち (a) を採用)。
     *
     * <p>旧実装は {@code AtomicReference<Map>} を持ち、{@code grant()} が
     * 「現在の参照を読む→その参照へ書く」の2段、{@code drain()} が
     * {@code getAndSet(new Map)} で参照を丸ごと差し替える1段、という非対称な構造だった。
     * {@code grant()} が参照を読んだ<b>直後</b>に {@code drain()} が参照を差し替えると、
     * {@code grant()} の書き込み先は「もう {@code pending} から辿れなくなった旧マップ」になり、
     * その分のEXPは例外もログも無いまま永久に失われる。
     *
     * <p>(a) を選んだ理由: (b)「差し替え後に旧マップへ後から入った分を次回バッチへ再合流」は
     * 「旧マップへの書き込みが本当に無くなったこと」をどう確認するかという新たな競合を生む
     * (再合流のタイミング自体がレースになる)。(c)「付与と回収を同じロックで囲む」は毎秒の
     * 高頻度パス({@code grant})へロック競合を持ち込み、この dispatcher が「非同期1秒間隔へ
     * まとめて集約する」ために存在する意図(ロックフリーな高頻度書き込み)と真っ向から反する。
     * (a) は参照を一切差し替えないため grant 側は今までどおりロックフリーのままで、
     * 回収漏れは構造的に起こり得ない ── {@code remove(key, value)} は「読んだ値と現在値が
     * 一致するときだけ」削除するアトミック操作なので、キー剥がしの間に割り込んだ grant は
     * 必ず値の不一致として検知され、削除されずにそのまま {@code pending} へ残る
     * (＝次回の drain へ確実に繰り越される。1円も消えない)。
     */
    private final ConcurrentHashMap<Key, Double> pending = new ConcurrentHashMap<>();
    /** {@link #collectBatch()} だけがアクセスする(drain は synchronized なので単一スレッド専用)。 */
    private final Map<Key, Integer> consecutiveRaceMisses = new HashMap<>();
    /** 遅延警告を1キーにつき1回だけに絞る({@link #collectBatch()} 参照)。 */
    private final Set<Key> raceDelayWarned = new HashSet<>();
    /**
     * テスト専用フック。{@link #collectBatch()} がスナップショットを取った直後・
     * 各キーの {@code remove} を試みる前に一度だけ呼ばれる。ここで {@link #grant} を呼ぶと、
     * 「回収の最中に割り込む grant」というレース窓を実スレッドの競争に頼らず決定的に再現できる。
     * 既定は no-op。
     */
    volatile Runnable collectionRaceHookForTests = () -> { };

    private final NativeProgressionService progression;
    private final BukkitTask task;
    private volatile boolean closed;
    // EXP獲得/レベルアップのプレイヤー向け表示 (S5/S6)。未配線可(null)。呼び出しは付与を絶対に妨げない。
    private volatile SkillExpFeedback feedback;
    /**
     * 職業(スキル)EXPへの任意ブースト(2026-07-25、digging.yml C-2「消費したシャベルの耐久値の総量に
     * 応じて職業経験値の取得量がアップ」向け)。{@code (playerId, skillId) -> ボーナスfraction(0.0+)}。
     * 未配線可(null=ブーストなし)。呼び出し失敗はバッチ全体を絶対に妨げない({@link #drain}参照)。
     */
    private volatile java.util.function.BiFunction<UUID, String, Double> jobExpMultiplierResolver;
    /**
     * ゲームプレイ由来のEXP付与を丸ごと止める述語 (2026-07-27、AFK対策)。{@code true} を返した
     * プレイヤーの {@link #grant} は<b>バッチに積まれる前に</b>捨てられる。
     *
     * <p>ここが唯一のゲームプレイEXPの合流点なので、経路ごとに抑止を書き足す必要がない。
     * 管理コマンド({@code /tf progression exp} 等)は {@code NativeProgressionService} を直接叩く
     * 別経路なので、この述語の影響を受けない — これは意図した線引きで、放置中でも運営付与は通る。
     * 未配線(null)なら抑止なし。
     */
    private volatile java.util.function.Predicate<UUID> grantSuppressor;

    public NativeExperienceDispatcher(Plugin plugin, NativeProgressionService progression) {
        this.progression = progression;
        this.task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::drain, 20L, 20L);
    }

    /** Unit-test constructor that does not schedule Bukkit tasks. */
    public NativeExperienceDispatcher(NativeProgressionService progression) {
        this.progression = Objects.requireNonNull(progression, "progression");
        this.task = null;
    }

    /** EXP獲得/レベルアップ表示の受け口を設定する (S5/S6)。null で無効化。 */
    public void setFeedback(SkillExpFeedback feedback) {
        this.feedback = feedback;
    }

    /** 職業EXPブーストの受け口を設定する(2026-07-25、digging.yml C-2)。null で無効化。 */
    public void setJobExpMultiplierResolver(java.util.function.BiFunction<UUID, String, Double> resolver) {
        this.jobExpMultiplierResolver = resolver;
    }

    /** ゲームプレイEXPの抑止述語を設定する(2026-07-27、AFK対策)。null で無効化。 */
    public void setGrantSuppressor(java.util.function.Predicate<UUID> suppressor) {
        this.grantSuppressor = suppressor;
    }

    public void grant(UUID playerId, String skillId, double amount) {
        if (closed || playerId == null || skillId == null
                || !Double.isFinite(amount) || amount == 0.0) return;
        if (isSuppressed(playerId)) return;
        pending.merge(new Key(playerId, skillId), amount, Double::sum);
    }

    /**
     * 抑止述語の評価。述語側の例外でEXP付与経路そのものを落とさない — 抑止は付加機能なので、
     * 判定に失敗したら「抑止しない」(＝従来どおり付与する)へ倒す。
     */
    private boolean isSuppressed(UUID playerId) {
        java.util.function.Predicate<UUID> suppressor = this.grantSuppressor;
        if (suppressor == null) {
            return false;
        }
        try {
            return suppressor.test(playerId);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "[progression] grant suppressor failed for " + playerId, ex);
            return false;
        }
    }

    /**
     * {@link #pending} からこの回に処理する分だけをアトミックに剥がして返す(W-314 タスク1)。
     * まずスナップショットを取り、キーごとに {@code pending.remove(key, snapshotValue)} を試す:
     * <ul>
     *   <li>成功 = スナップショット後に誰も書き足していない。そのままバッチへ積む。</li>
     *   <li>失敗 = スナップショットとremoveの間に {@link #grant} が割り込んで値が変わった。
     *       現在値には割り込んだ分もすでに合算されているので、<b>ここでは何も削除せず</b>
     *       次回の {@link #drain} (1秒後)へそのまま繰り越す。取りこぼしではなく先送り。</li>
     * </ul>
     * 同じキーで失敗が続けて {@link #RACE_MISS_WARN_THRESHOLD} 回に達したら、
     * データは消えていないが回収が異常に遅延している事実を1回だけ警告する。
     */
    private Map<Key, Double> collectBatch() {
        Map<Key, Double> snapshot = new HashMap<>(pending);
        collectionRaceHookForTests.run();
        Map<Key, Double> batch = new HashMap<>();
        for (Map.Entry<Key, Double> entry : snapshot.entrySet()) {
            Key key = entry.getKey();
            Double value = entry.getValue();
            if (pending.remove(key, value)) {
                batch.put(key, value);
                consecutiveRaceMisses.remove(key);
                raceDelayWarned.remove(key);
            } else {
                int misses = consecutiveRaceMisses.merge(key, 1, Integer::sum);
                if (misses >= RACE_MISS_WARN_THRESHOLD && raceDelayWarned.add(key)) {
                    LOG.log(Level.WARNING, "[progression] EXP grant collection for "
                            + key.playerId() + " / " + key.skillId() + " has been deferred across "
                            + misses + " consecutive drain cycles by continuous concurrent grants."
                            + " No EXP is lost, but this key is not catching up; investigate the"
                            + " grant frequency for this player/skill.");
                }
            }
        }
        return batch;
    }

    /**
     * Applies every coalesced grant in the current batch. Each entry is isolated: a failure on
     * one player/skill key is retried (bounded) or dropped in place and never aborts the rest of
     * the batch. The catch clause deliberately covers {@link RuntimeException} broadly — not just
     * {@link IllegalStateException}/{@link IllegalArgumentException} — because a corrupt EXP
     * curve formula surfaces as a {@code FormulaParseException} only when a skill actually earns
     * EXP (curve evaluation is lazy); letting that propagate out of this method would abort the
     * whole batch and permanently lose every other player's pending EXP for this tick.
     */
    public synchronized void drain() {
        Map<Key, Double> batch = collectBatch();
        if (batch.isEmpty()) {
            return;
        }
        // 職業EXPブースト(2026-07-25): バッチ確定直後に一度だけ適用する(retryループ内で適用すると
        // 一時失敗によるリトライのたびに重ねがけされてしまうため、ここで確定させてからretryへ渡す)。
        applyJobExpMultiplier(batch);
        Map<Key, Integer> attempts = new HashMap<>();
        Map<Key, Double> retry = new HashMap<>(batch);
        while (!retry.isEmpty()) {
            Map<Key, Double> nextRetry = new HashMap<>();
            for (Map.Entry<Key, Double> entry : retry.entrySet()) {
                Key key = entry.getKey();
                double amount = entry.getValue();
                try {
                    NativeProgressionService.GrantResult result =
                            progression.grantExp(key.playerId(), key.skillId(), amount);
                    notifyFeedback(key, amount, result);
                } catch (IllegalArgumentException ex) {
                    // Non-transient (e.g. unknown skill id): retrying would fail identically.
                    LOG.log(Level.WARNING,
                            "[progression] Dropping invalid EXP grant for "
                                    + key.playerId() + " / " + key.skillId(),
                            ex);
                } catch (RuntimeException ex) {
                    // Covers IllegalStateException (transient storage failure) and any other
                    // unexpected failure (e.g. a malformed exp_level_curve formula) alike: bound
                    // the retries per key so one bad entry can never loop forever or take down
                    // the rest of the pending batch.
                    int attempt = attempts.merge(key, 1, Integer::sum);
                    if (attempt < MAX_TRANSIENT_RETRIES) {
                        nextRetry.put(key, amount);
                        LOG.log(Level.WARNING,
                                "[progression] Retrying EXP grant after transient failure for "
                                        + key.playerId() + " / " + key.skillId()
                                        + " (attempt " + attempt + ")",
                                ex);
                    } else {
                        LOG.log(Level.SEVERE,
                                "[progression] Dropping EXP grant after "
                                        + MAX_TRANSIENT_RETRIES + " failures for "
                                        + key.playerId() + " / " + key.skillId(),
                                ex);
                    }
                }
            }
            retry = nextRetry;
        }
    }

    /**
     * {@link #jobExpMultiplierResolver}が設定されていれば、バッチ内の各エントリへ一度だけ
     * {@code amount *= (1 + bonus)}を適用する。resolver未配線/例外/非有限値/負値はすべて
     * no-op(fail-soft) — 一件の解決失敗がバッチ全体を止めることは絶対にない。
     */
    private void applyJobExpMultiplier(Map<Key, Double> batch) {
        java.util.function.BiFunction<UUID, String, Double> resolver = jobExpMultiplierResolver;
        if (resolver == null) return;
        for (Map.Entry<Key, Double> entry : batch.entrySet()) {
            Key key = entry.getKey();
            double bonus;
            try {
                Double resolved = resolver.apply(key.playerId(), key.skillId());
                bonus = resolved == null ? 0.0 : resolved;
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "[progression] job-exp multiplier resolver failed for "
                        + key.playerId() + " / " + key.skillId() + " (ignored)", ex);
                bonus = 0.0;
            }
            if (Double.isFinite(bonus) && bonus > 0.0) {
                batch.put(key, entry.getValue() * (1.0 + bonus));
            }
        }
    }

    /** フィードバック(表示)呼び出し。表示側の失敗が EXP 付与バッチを絶対に壊さないよう完全に隔離する。 */
    private void notifyFeedback(Key key, double amount, NativeProgressionService.GrantResult result) {
        SkillExpFeedback sink = feedback;
        // result.after()==null は GrantResult.unchanged(DB一時障害/amount=0でEXP付与が捨てられた)ケース。
        // bossbar表示は after.level() を触るためメインスレッド(runTask内=本try/catch外)でNPEになる。
        // 付与が起きていない以上フィードバック自体不要なので、ここで確実に弾く。
        if (sink == null || result == null || result.after() == null) {
            return;
        }
        try {
            sink.onExpGranted(key.playerId(), key.skillId(), amount, result);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "[progression] EXP feedback failed for "
                    + key.playerId() + " / " + key.skillId() + " (ignored)", ex);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (task != null) {
            task.cancel();
        }
        drain();
    }

    private record Key(UUID playerId, String skillId) {
    }
}
