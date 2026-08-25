package com.trinityforge.progression;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「サーバが知っているプレイヤー位置」から<b>1tickぶん先読みした位置</b>を出す小道具
 * (2026-08-25 / W-247・W-248)。頭上の称号({@link TitleDisplayService})と装備パーティクル
 * ({@link ParticleEffectService})の<b>追従の遅れ</b>を同じ理屈で詰めるために共有する。
 *
 * <h2>なぜ先読みが要るのか(遅れの正体)</h2>
 * プラグインが毎tick読む {@code player.getLocation()} は<b>クライアントが送ってきた位置</b>で、
 * 送信 → サーバ適用 → 描画までに必ず遅れが乗る。一方で
 * <ul>
 *   <li><b>自分の本体</b>はクライアントが自前で予測して即座に描く(遅れ 0)。</li>
 *   <li><b>ネームタグ</b>は本体と同じエンティティなので、本体と完全に同じ動きをする。</li>
 * </ul>
 * つまり「サーバ由来の位置に置いたもの」は、本体・ネームタグに対して<b>構造的に後ろへズレる</b>。
 * 称号の補間長を本体と揃えても(2026-08-24 / W-212)このズレは残る ── 揃えたのは
 * <em>補間の長さ</em>で、<em>目標地点そのものが過去</em>だったから。
 *
 * <p>そこで<b>速度 × 先読みtick</b>だけ進めた位置を目標にする。等速で走っている間はズレが消え、
 * 加速・減速の瞬間だけ僅かに行き過ぎる(その誤差は最大で「1tickの移動量」＝走行時 0.3 ブロック弱)。
 *
 * <h2>速度は {@code getVelocity()} から取らない</h2>
 * プレイヤーの {@code getVelocity()} はサーバ側の {@code deltaMovement} で、
 * <b>クライアント操作で動くプレイヤーでは当てにならない</b>(移動パケットの適用は位置の上書きで、
 * 速度は更新されない経路がある)。ここでは<b>前回自分が見た位置との差</b>を毎tick自分で取る ──
 * 「1tickでどれだけ動いたか」は定義そのものであり、実装依存の値を信じる必要がない。
 *
 * <p>{@link #sample} は<b>1tickに1回だけ</b>呼ぶこと。呼ぶ間隔が2tickになれば差分も2tickぶんになり、
 * 先読みが倍になる(装備パーティクルは発生間隔が10tick以上なので、駆動側は
 * 「毎tick sample して、発生させるtickだけ結果を使う」形にしてある)。
 */
public final class MotionLead {

    /**
     * 先読みの既定tick数。
     *
     * <p>1 にしているのは「サーバが持っている位置は<b>1tick前のクライアント位置</b>」という
     * 前提から。ここを大きくすると走り出しでオーバーシュートし、0 にすると 2026-08-25 以前の
     * 遅れが戻る。<b>実機で詰めるための数値なので、変えたときは走る/止まる/方向転換の3つで見る。</b>
     */
    public static final double DEFAULT_LEAD_TICKS = 1.0;

    /**
     * 先読みで足せる距離の上限(ブロック)。
     *
     * <p><b>安全弁</b>: テレポート・ダンジョン転送・エリトラ・ノックバックでは1tickの差分が
     * 数十〜数千ブロックになる。そのまま掛けると<b>称号が地平線の彼方へ飛び、パーティクルが
     * 見えない場所に出る</b>。上限を超えた差分は「移動ではない」と見なして先読みを捨てる方が安全。
     */
    public static final double MAX_LEAD_BLOCKS = 1.0;

    /**
     * これ以下の水平移動は「止まっている」とみなす閾値(ブロック/tick)。
     * {@link #travelYaw} が向きを決められないので、向いている方向へ倒す。
     */
    private static final double STILL_EPSILON = 0.01;

    private final Map<UUID, Location> lastSeen = new ConcurrentHashMap<>();

    /**
     * {@code player} の現在位置を1件記録し、<b>先読みオフセット</b>を返す。
     *
     * @return {@code {dx, dy, dz}}。初回・ワールド跨ぎ・上限超えは全て 0(＝先読みしない)
     */
    public double[] sample(Player player, double leadTicks) {
        Location current = player.getLocation();
        Location previous = lastSeen.put(player.getUniqueId(), current.clone());
        if (previous == null || previous.getWorld() == null
                || !previous.getWorld().equals(current.getWorld())) {
            return new double[] {0.0, 0.0, 0.0};
        }
        return leadOffset(current.getX() - previous.getX(),
                current.getY() - previous.getY(),
                current.getZ() - previous.getZ(), leadTicks);
    }

    /** 最後に見た位置を捨てる(退出・死亡・ワールド跨ぎ)。次の {@link #sample} は先読み 0 になる。 */
    public void forget(UUID playerId) {
        lastSeen.remove(playerId);
    }

    /** 追跡している人数(テスト・診断用)。 */
    int trackedCount() {
        return lastSeen.size();
    }

    /**
     * 1tickの移動量 → 先読みオフセット(純関数)。
     *
     * <p>上限を「超えた成分だけ縮める」のではなく<b>丸ごと 0 にする</b>のが肝。縮めると
     * テレポート直後に「1ブロックだけズレた位置」へ出てしまい、原因の分からない不整合になる。
     * 移動ではないと判った差分は使わない、が正しい。
     */
    static double[] leadOffset(double dx, double dy, double dz, double leadTicks) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || !Double.isFinite(leadTicks) || leadTicks <= 0.0) {
            return new double[] {0.0, 0.0, 0.0};
        }
        double lx = dx * leadTicks;
        double ly = dy * leadTicks;
        double lz = dz * leadTicks;
        double distanceSq = lx * lx + ly * ly + lz * lz;
        if (distanceSq > MAX_LEAD_BLOCKS * MAX_LEAD_BLOCKS) {
            return new double[] {0.0, 0.0, 0.0};
        }
        return new double[] {lx, ly, lz};
    }

    /**
     * 進んでいる向き(Bukkit の yaw、度)。止まっているときは {@code fallbackYaw}
     * (＝向いている方向)を返す。{@link com.trinityforge.config.domains.SpecialRewardsConfig.Shape#TRAIL}
     * が「後ろ」を決めるのに使う。
     *
     * <p>Bukkit の yaw は「南(+Z)が 0 で時計回り」＝前方が {@code (-sin(yaw), cos(yaw))} なので、
     * 進行方向 {@code (dx, dz)} に対応する yaw は {@code atan2(-dx, dz)}。
     */
    static double travelYaw(double dx, double dz, double fallbackYaw) {
        if (!Double.isFinite(dx) || !Double.isFinite(dz)) {
            return fallbackYaw;
        }
        if (Math.abs(dx) < STILL_EPSILON && Math.abs(dz) < STILL_EPSILON) {
            return fallbackYaw;
        }
        return Math.toDegrees(Math.atan2(-dx, dz));
    }
}
