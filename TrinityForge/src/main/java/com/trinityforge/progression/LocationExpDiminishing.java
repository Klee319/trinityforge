package com.trinityforge.progression;

import com.trinityforge.config.domains.SkillExpConfig;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TT(トラップタワー)・放置狩り対策 (2026-07-26 オーバーワールドEXP開放にともなう要件):
 * <b>同じ場所で稼ぎ続けると獲得EXPが逓減する</b>。
 *
 * <p>既存の対策は「誰が倒したか」しか見ていなかった — {@code MobOverrideExpListener} の player-kill
 * ゲートは溶岩/落下/モブ同士の相打ちを弾き、{@code AttackerTargetCooldown} は同一個体への連打を弾く。
 * どちらも<b>プレイヤーが次々と湧く別個体を殴り続ける</b>という、TTの本命の形には効かない。この
 * クラスはその一点だけを担当する。
 *
 * <p>判定は「直近 {@code window-seconds} 秒のあいだに、半径 {@code radius} ブロック以内で何回
 * EXPを得たか」。{@code threshold} 回までは倍率1.0(=無傷)で、それを超えたぶんだけ1回あたり
 * {@code decay-per-kill} ずつ減り、{@code floor} で下げ止まる。完全に0にはしない — 「稼げなくなる」
 * ではなく「割に合わなくなる」ことが狙いで、TTを潰すのではなく主戦場をダンジョンへ寄せるのが目的。
 *
 * <p><b>ダンジョンは既定で対象外</b>({@code exempt-dungeon-worlds})。ダンジョンは湧き潰しでなく
 * 有限のスポーンを消化する場所で、しかもアリーナ型だと1箇所に留まって戦うのが正常な遊び方なので、
 * ここで逓減させると本来推奨したい遊び方を罰することになる。
 *
 * <p>記録の単位はプレイヤー個人。武器EXP(命中)・防具EXP(被弾)・バニラEXP(撃破)のどれもが同じ
 * カウンタを共有する — 「この場所でどれだけ稼いだか」が判定軸なので、経路ごとに別勘定にすると
 * 経路を混ぜるだけで回避できてしまう。
 */
public final class LocationExpDiminishing implements Listener {

    /**
     * 1件の撃破地点。ワールドをまたいだ座標比較を避けるため world UUID を持ち、
     * 二重記録を弾くために倒した相手の UUID も持つ({@link #recordKill} 参照)。
     */
    private record Spot(long timeMillis, UUID worldId, double x, double y, double z, UUID victimId) {

        boolean within(UUID otherWorld, double ox, double oy, double oz, double radiusSquared) {
            if (!worldId.equals(otherWorld)) {
                return false;
            }
            double dx = x - ox;
            double dy = y - oy;
            double dz = z - oz;
            return dx * dx + dy * dy + dz * dz <= radiusSquared;
        }
    }

    private final Map<UUID, ArrayDeque<Spot>> recentByPlayer = new ConcurrentHashMap<>();

    /**
     * この地点に掛けるべき倍率 [floor, 1.0]。<b>読み取り専用でカウンタは増やさない</b>。
     *
     * <p>カウンタを増やすのは {@link #recordKill} だけ、つまり<b>撃破のみ</b>。ここを「呼ぶたびに
     * 1件記録する」設計にしていたときは、被弾トリガの防具EXPと命中トリガの武器EXPでもカウンタが
     * 増えてしまい、5体を相手に各6発もらえば同一地点30件に達して<b>正常なプレイでEXPが減り始める</b>
     * という誤爆になっていた。TTの本質は撃破数なので、数えるのは撃破だけでよい。
     *
     * @param where 判定する場所。撃破EXPなら被害者の位置、被弾EXPならプレイヤーの位置。
     */
    public double multiplierAt(Player player, Location where, SkillExpConfig config,
                               boolean inDungeonWorld) {
        if (player == null || where == null || where.getWorld() == null
                || !config.spotDiminishingEnabled()) {
            return 1.0;
        }
        if (inDungeonWorld && config.spotDiminishingExemptsDungeons()) {
            return 1.0;
        }
        long now = System.currentTimeMillis();
        long windowMillis = (long) (config.spotDiminishingWindowSeconds() * 1000.0);
        double radiusSquared = config.spotDiminishingRadius() * config.spotDiminishingRadius();
        UUID worldId = where.getWorld().getUID();

        ArrayDeque<Spot> history = recentByPlayer.get(player.getUniqueId());
        if (history == null) {
            return 1.0;
        }
        int nearby = 0;
        synchronized (history) {
            pruneExpired(history, now, windowMillis);
            for (Spot spot : history) {
                if (spot.within(worldId, where.getX(), where.getY(), where.getZ(), radiusSquared)) {
                    nearby++;
                }
            }
        }
        return multiplier(nearby, config.spotDiminishingThreshold(),
                config.spotDiminishingDecayPerKill(), config.spotDiminishingFloor());
    }

    /**
     * 撃破を1件記録する。倍率の計算はしない — 呼び出し側は「先に {@link #multiplierAt} で倍率を読み、
     * それから記録する」順で使うこと(今回の撃破が自分自身の倍率を下げてしまわないように)。
     *
     * <p>同じ死亡イベントに対して {@code MobLevelTableListener}(HIGH)と
     * {@code MobOverrideExpListener}(MONITOR)の両方が反応しうるため、直前の記録と倒した相手が同じなら
     * 二重記録として捨てる。同一プレイヤーぶんの処理は同じイベント内で連続して走るので、重複は必ず
     * 直前の1件になる。
     */
    public void recordKill(Player player, Location where, UUID victimId, SkillExpConfig config,
                           boolean inDungeonWorld) {
        if (player == null || where == null || where.getWorld() == null
                || !config.spotDiminishingEnabled()) {
            return;
        }
        if (inDungeonWorld && config.spotDiminishingExemptsDungeons()) {
            return;
        }
        long now = System.currentTimeMillis();
        long windowMillis = (long) (config.spotDiminishingWindowSeconds() * 1000.0);
        ArrayDeque<Spot> history = recentByPlayer.computeIfAbsent(
                player.getUniqueId(), id -> new ArrayDeque<>());
        synchronized (history) {
            pruneExpired(history, now, windowMillis);
            Spot last = history.peekLast();
            if (last != null && victimId != null && victimId.equals(last.victimId())) {
                return;
            }
            history.addLast(new Spot(now, where.getWorld().getUID(),
                    where.getX(), where.getY(), where.getZ(), victimId));
        }
    }

    /**
     * 窓から出たものを先頭から捨てる。時刻昇順で積んでいるので、先頭が生きていればそれ以降も
     * 全て生きている = 全走査は要らない。
     */
    private static void pruneExpired(ArrayDeque<Spot> history, long now, long windowMillis) {
        while (!history.isEmpty() && now - history.peekFirst().timeMillis() > windowMillis) {
            history.pollFirst();
        }
    }

    /**
     * 撃破EXP用の薄いラッパ。被害者の位置で判定する。
     *
     * <p>{@code inDungeonWorld} は呼び出し側が渡す — ここで {@code TrinityForge.getInstance()} から
     * 引くと、プラグイン起動を前提にしないリスナー(例: {@code MobOverrideExpListener} は元々
     * シングルトン非依存だった)がユニットテストで NPE になる。判定材料は呼び出し側が既に持っている。
     */
    public double multiplierForKillSpot(Player player, Entity victim, SkillExpConfig config,
                                        boolean inDungeonWorld) {
        if (victim == null) {
            return 1.0;
        }
        return multiplierAt(player, victim.getLocation(), config, inDungeonWorld);
    }

    /** ログアウトで履歴を捨てる(サーバ稼働時間に比例してMapが太り続けるのを防ぐ)。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        recentByPlayer.remove(event.getPlayer().getUniqueId());
    }

    /**
     * バニラ経験値オーブ量へ撃破地点の逓減を掛ける。
     *
     * <p>バニラEXPを書き換えるリスナーは2つある({@code MobLevelTableListener} の帯 {@code vanilla-exp}
     * =HIGH と、{@code MobOverrideExpListener} のモブ別ランプ=MONITORで後勝ち)。片方にだけ逓減を
     * 入れると「モブ別ランプが設定されたモブだけ素通り」という経路依存の穴になるため、両方から
     * ここを呼ぶ。プレイヤーキル判定は呼び出し元が済ませている前提だが、{@code getKiller()} が
     * null でも安全に素通しする — このメソッドの責務は「減らす」ことだけで、付与可否は判断しない。
     *
     * <p>切り捨てだが1未満にはしない: 倍率が下限で止まる以上、元が1以上のときに0個になるのは
     * 挙動としてバグに見えるため。
     */
    public int applyToVanillaExp(int baseExp, LivingEntity victim, SkillExpConfig config,
                                 boolean inDungeonWorld) {
        if (baseExp <= 0 || victim == null) {
            return Math.max(0, baseExp);
        }
        Player killer = victim.getKiller();
        if (killer == null) {
            return baseExp;
        }
        // 順序が重要: 先に倍率を読んでから記録する。逆にすると今回の撃破が自分自身の倍率を下げる。
        double multiplier = multiplierForKillSpot(killer, victim, config, inDungeonWorld);
        recordKill(killer, victim.getLocation(), victim.getUniqueId(), config, inDungeonWorld);
        if (multiplier >= 1.0) {
            return baseExp;
        }
        return Math.max(1, (int) Math.floor(baseExp * multiplier));
    }

    /**
     * バニラEXPを書き換えるリスナー用の入口。プラグインが起動していない(ユニットテスト等)場合は
     * 逓減を掛けずそのまま返す — このクラスはEXPの「縮小」だけを担当し、動かないなら何もしないのが
     * 正しい失敗の仕方だから。プラグイン起動後は本来のインスタンスと設定で判定する。
     */
    public static int applyIfRunning(int baseExp, LivingEntity victim) {
        com.trinityforge.TrinityForge tf = com.trinityforge.TrinityForge.getInstance();
        if (tf == null || victim == null || victim.getWorld() == null) {
            return Math.max(0, baseExp);
        }
        boolean inDungeon = tf.dungeonWorldRegistry().isDungeonWorld(victim.getWorld().getUID());
        return tf.locationExpDiminishing()
                .applyToVanillaExp(baseExp, victim, tf.config().skillExp(), inDungeon);
    }

    /**
     * 純粋関数の本体。{@code priorCount} は「この地点で既に稼いだ回数」(今回ぶんを含まない)。
     *
     * <p>{@code threshold} 回まではそのまま。超えた1回目で {@code decayPerKill} だけ減り、以降
     * 線形に下がって {@code floor} で止まる。指数減衰でなく線形にしたのは、しきい値と下げ止まりが
     * 決まっていれば「何回目でどこまで落ちるか」を運営が暗算できるほうが調整しやすいため。
     */
    static double multiplier(int priorCount, int threshold, double decayPerKill, double floor) {
        double safeFloor = Math.max(0.0, Math.min(1.0, floor));
        if (priorCount < threshold) {
            return 1.0;
        }
        int over = priorCount - threshold + 1;
        double value = 1.0 - over * Math.max(0.0, decayPerKill);
        return Math.max(safeFloor, Math.min(1.0, value));
    }
}
