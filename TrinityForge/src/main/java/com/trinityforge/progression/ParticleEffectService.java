package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * パーティクル(particles) 周期表示 (2026-07-23-stat-gate-overhaul §6.1): 装備中プレイヤーの周囲へ
 * config指定パーティクルを周期的に発生させる。「他人の演出を非表示」トグル対応のため、ブロードキャスト
 * ({@code World#spawnParticle}) ではなく {@link Player#spawnParticle} で閲覧者ごとに個別送信する
 * (自分自身の演出は常に見える。他人の演出は閲覧者側の {@code hide-others} トグルで抑制)。
 *
 * <p>2026-07-23 verifier指摘⑨: 装備中パーティクルIDは毎tickのPDC読みを避けるためメモリキャッシュする。
 * キャッシュは装備変更({@link #invalidate})/join/quitで無効化され、次回参照時に {@link PlayerData} から
 * 再読込される。{@code particles} 定義が空ならtick自体が早期returnする。
 */
public final class ParticleEffectService implements Listener {

    /** ベースtick周期。各パーティクル定義の interval-ticks はこの倍数として扱う(端数切捨て)。 */
    private static final long BASE_PERIOD_TICKS = 1L;
    private static final double VIEW_RADIUS = 32.0;

    private final Plugin plugin;
    private final SpecialRewardsConfig config;
    private final ConcurrentHashMap<UUID, Optional<String>> equippedCache = new ConcurrentHashMap<>();
    /**
     * 追従の遅れを詰めるための先読み(2026-08-25 / W-247)。
     * <b>毎tick sample し、発生させるtickだけ結果を使う</b> ── 差分は「前回 sample からの移動量」なので、
     * 発生間隔(10tick 等)ごとにしか sample しないと 10tick ぶんの移動量になり先読みが暴れる。
     */
    private final MotionLead motion = new MotionLead();
    private long tickCounter;
    private BukkitTask task;

    public ParticleEffectService(Plugin plugin, SpecialRewardsConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, BASE_PERIOD_TICKS, BASE_PERIOD_TICKS);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        equippedCache.clear();
    }

    /** 装備変更確定直後に呼ぶ(例: {@code SettingsGui#setOnParticleChanged})。次回参照時に再読込される。 */
    public void invalidate(Player player) {
        equippedCache.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        equippedCache.remove(event.getPlayer().getUniqueId());
        // 参加直前の位置との差分は「移動」ではない(別ワールド/別座標からの復帰)。
        motion.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        equippedCache.remove(event.getPlayer().getUniqueId());
        motion.forget(event.getPlayer().getUniqueId());
    }

    private void tick() {
        if (config.particles().isEmpty()) {
            return;
        }
        tickCounter++;
        for (Player owner : Bukkit.getOnlinePlayers()) {
            if (owner.isDead()) {
                continue;
            }
            Optional<String> equippedId = equippedCache.computeIfAbsent(
                    owner.getUniqueId(), id -> PlayerData.of(owner).equippedParticle());
            if (equippedId.isEmpty()) {
                continue;
            }
            // ⚠ 先読みの sample は発生間隔に関係なく【毎tick】。下の interval 判定より前に置くこと。
            double[] lead = motion.sample(owner, MotionLead.DEFAULT_LEAD_TICKS);
            SpecialRewardsConfig.ParticleEffect effect = config.particles().get(equippedId.get());
            if (effect == null || effect.intervalTicks() <= 0 || tickCounter % effect.intervalTicks() != 0) {
                continue;
            }
            render(owner, effect, lead);
        }
    }

    /**
     * ワンショットのパーティクル発生(パーティクルシード起動時 = {@code ParticleSeedListener} 用)。
     * 同じ「他人の演出非表示」トグルを尊重する per-viewer 送信。
     *
     * <p><b>{@code anchor} は「道具が実際に当たった場所」を渡す</b>(2026-08-25 / W-242)。
     * 2026-08-25 より前はここが常に {@code origin.getLocation()} で、
     * <b>壊したブロックや殴った敵ではなくプレイヤーの足元から粒子が出ていた</b>。
     * どこを基準にするかは呼び出し側(config の {@code origin:})の判断で、この層では決めない。
     *
     * @param origin   演出の所有者(「自分の演出は常に見える」の基準)
     * @param anchor   発生の基準座標。{@code y-offset} はここから足される
     * @param yaw      {@link SpecialRewardsConfig.Shape#ARC} が向きに使う角度(度)
     */
    public static void burst(Player origin, Location anchor, Particle particle,
                             SpecialRewardsConfig.Emission emission, double yaw) {
        if (origin == null || anchor == null || particle == null || emission == null) {
            return;
        }
        if (anchor.getWorld() == null || !anchor.getWorld().equals(origin.getWorld())) {
            return;
        }
        // ワンショット(シード)は「当たった場所」＝静止した座標なので先読みは要らない。
        emitFor(origin, anchor, anchor, particle, emission, yaw);
    }

    /**
     * 装備パーティクル1回ぶん。
     *
     * <h2>先読みは「自分の視点」だけに掛ける(2026-08-25 / W-247)</h2>
     * <b>自分の本体はクライアントが予測して即座に描く</b>ので、サーバ位置に出した粒子は必ず後ろへズレる
     * (実サーバ報告「位置同期が遅い」)。一方<b>他人から見た本体は逆に補間で遅れて描かれる</b>ので、
     * 同じ先読みを他人にも掛けると今度は<b>本体より前に粒子が出る</b>。
     * 送信は元々 per-viewer なので、閲覧者が本人かどうかで基準座標を替えるのが正しい。
     *
     * <h2>{@code TRAIL} だけ「進んでいる向き」を渡す</h2>
     * 軌跡は<b>走った跡</b>として見せるものなので、向いている方向の後ろではなく
     * <b>進行方向の逆</b>へ伸ばす(横歩き・後ろ歩きでも足元から後ろへ流れる)。
     * 止まっているときは向いている方向へ倒れる({@link MotionLead#travelYaw})。
     */
    private void render(Player owner, SpecialRewardsConfig.ParticleEffect effect, double[] lead) {
        Location anchor = owner.getLocation();
        Location ownerAnchor = anchor.clone().add(lead[0], lead[1], lead[2]);
        emitFor(owner, anchor, ownerAnchor, effect.particle(), effect.emission(),
                emitYaw(effect.emission(), anchor.getYaw(), lead));
    }

    /**
     * その発生に使う「向き」。{@link SpecialRewardsConfig.Shape#TRAIL} だけ
     * <b>進んでいる向き</b>(止まっているときは向いている方向)、それ以外は<b>向いている方向</b>。
     *
     * <p>package-private なのは試験のため ―― MockBukkit は {@code Player#spawnParticle} を
     * 観測できないので、発生そのものからは「軌跡が進行方向の後ろへ出ているか」を確かめられない。
     */
    static double emitYaw(SpecialRewardsConfig.Emission emission, float lookYaw, double[] lead) {
        if (emission == null || emission.shape() != SpecialRewardsConfig.Shape.TRAIL) {
            return lookYaw;
        }
        return MotionLead.travelYaw(lead[0], lead[2], lookYaw);
    }

    /**
     * {@link ParticleGeometry} が出した呼び出し列を、閲覧者ごとに撃つ。
     *
     * <p>ここが唯一の {@code spawnParticle} 呼び出し口。形状の追加はすべて
     * {@link ParticleGeometry#emits} 側で済み、この層は触らない ── 形状ごとに
     * {@code spawnParticle} を書き分けていた頃は、{@code speed}(Bukkit の {@code extra})が
     * <b>どの形状でも 0 固定</b>で、円形拡散のような「向きのある演出」が原理的に書けなかった。
     */
    private static void emitFor(Player owner, Location anchor, Location ownerAnchor, Particle particle,
                                SpecialRewardsConfig.Emission emission, double yaw) {
        java.util.List<ParticleGeometry.Emit> emits = ParticleGeometry.emits(emission, yaw);
        if (emits.isEmpty()) {
            return;
        }
        for (Player viewer : owner.getWorld().getPlayers()) {
            if (viewer != owner && PlayerData.of(viewer).hideOthersCosmetics()) {
                continue;
            }
            if (viewer.getLocation().distanceSquared(anchor) > VIEW_RADIUS * VIEW_RADIUS) {
                continue;
            }
            Location base = viewer == owner ? ownerAnchor : anchor;
            for (ParticleGeometry.Emit emit : emits) {
                Location point = base.clone().add(emit.dx(), emit.dy(), emit.dz());
                viewer.spawnParticle(particle, point, emit.count(),
                        emit.offX(), emit.offY(), emit.offZ(), emit.extra());
            }
        }
    }
}
