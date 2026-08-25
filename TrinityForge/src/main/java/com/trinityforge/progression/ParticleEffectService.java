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
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        equippedCache.remove(event.getPlayer().getUniqueId());
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
            SpecialRewardsConfig.ParticleEffect effect = config.particles().get(equippedId.get());
            if (effect == null || effect.intervalTicks() <= 0 || tickCounter % effect.intervalTicks() != 0) {
                continue;
            }
            render(owner, effect);
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
        emitFor(origin, anchor, particle, emission, yaw);
    }

    private void render(Player owner, SpecialRewardsConfig.ParticleEffect effect) {
        emitFor(owner, owner.getLocation(), effect.particle(), effect.emission(),
                owner.getLocation().getYaw());
    }

    /**
     * {@link ParticleGeometry} が出した呼び出し列を、閲覧者ごとに撃つ。
     *
     * <p>ここが唯一の {@code spawnParticle} 呼び出し口。形状の追加はすべて
     * {@link ParticleGeometry#emits} 側で済み、この層は触らない ── 形状ごとに
     * {@code spawnParticle} を書き分けていた頃は、{@code speed}(Bukkit の {@code extra})が
     * <b>どの形状でも 0 固定</b>で、円形拡散のような「向きのある演出」が原理的に書けなかった。
     */
    private static void emitFor(Player owner, Location anchor, Particle particle,
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
            for (ParticleGeometry.Emit emit : emits) {
                Location point = anchor.clone().add(emit.dx(), emit.dy(), emit.dz());
                viewer.spawnParticle(particle, point, emit.count(),
                        emit.offX(), emit.offY(), emit.offZ(), emit.extra());
            }
        }
    }
}
