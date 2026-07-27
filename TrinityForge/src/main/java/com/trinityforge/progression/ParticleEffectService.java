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
     */
    public static void burstAt(Player origin, Particle particle, int count, double radius) {
        Location base = origin.getLocation().add(0, 1.0, 0);
        for (Player viewer : origin.getWorld().getPlayers()) {
            if (viewer != origin && PlayerData.of(viewer).hideOthersCosmetics()) {
                continue;
            }
            if (viewer.getLocation().distanceSquared(base) > VIEW_RADIUS * VIEW_RADIUS) {
                continue;
            }
            viewer.spawnParticle(particle, base, count, radius, radius, radius, 0.0);
        }
    }

    private void render(Player owner, SpecialRewardsConfig.ParticleEffect effect) {
        Location base = owner.getLocation().add(0, 1.0, 0);
        for (Player viewer : owner.getWorld().getPlayers()) {
            if (viewer != owner && PlayerData.of(viewer).hideOthersCosmetics()) {
                continue;
            }
            if (viewer.getLocation().distanceSquared(base) > VIEW_RADIUS * VIEW_RADIUS) {
                continue;
            }
            switch (effect.shape()) {
                case AURA -> viewer.spawnParticle(effect.particle(), base, effect.count(),
                        effect.radius(), effect.radius(), effect.radius(), 0.0);
                case CIRCLE -> {
                    for (double[] offset : ParticleGeometry.circleOffsets(effect.count(), effect.radius())) {
                        Location point = base.clone().add(offset[0], 0, offset[1]);
                        viewer.spawnParticle(effect.particle(), point, 1, 0, 0, 0, 0);
                    }
                }
            }
        }
    }
}
