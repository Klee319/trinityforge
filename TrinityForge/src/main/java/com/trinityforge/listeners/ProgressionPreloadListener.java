package com.trinityforge.listeners;

import com.trinityforge.progression.infrastructure.CachedProgressionRepository;
import com.trinityforge.progression.repository.ProgressionRepository;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.logging.Level;

/** Warms progression caches on join; best-effort and never blocks login on failure. */
public final class ProgressionPreloadListener implements Listener {

    private final Plugin plugin;
    private final ProgressionRepository repository;
    /**
     * 日次EXP逓減の状態(2026-07-31)。プレイヤー×スキルの指数移動窓をメモリに持つだけなので、
     * 退出時に捨ててメモリを有界にする。null 可(逓減を配線していない構成でも動く)。
     */
    private final com.trinityforge.progression.DailyExpDiminishing dailyExpDiminishing;
    /**
     * 逓減の蓄積の永続化(2026-08-18)。null 可(配線していない構成でも動く)。
     *
     * <p>これが無かった頃は退出時に {@code forget} で捨てるだけだったので、
     * <b>再ログインするだけで逓減が等倍へ戻せた</b>。機構としては動いているのに
     * 目的(1日の稼ぎ総量を薄める)を1ミリも達成していない状態だった。
     */
    private final com.trinityforge.progression.DailyExpWindowPersistence dailyExpPersistence;

    public ProgressionPreloadListener(Plugin plugin, ProgressionRepository repository) {
        this(plugin, repository, null, null);
    }

    public ProgressionPreloadListener(Plugin plugin, ProgressionRepository repository,
                                      com.trinityforge.progression.DailyExpDiminishing dailyExpDiminishing) {
        this(plugin, repository, dailyExpDiminishing, null);
    }

    public ProgressionPreloadListener(
            Plugin plugin, ProgressionRepository repository,
            com.trinityforge.progression.DailyExpDiminishing dailyExpDiminishing,
            com.trinityforge.progression.DailyExpWindowPersistence dailyExpPersistence) {
        this.dailyExpDiminishing = dailyExpDiminishing;
        this.dailyExpPersistence = dailyExpPersistence;
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var playerId = event.getPlayer().getUniqueId();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (repository instanceof CachedProgressionRepository cached) {
                    cached.preload(playerId);
                } else {
                    repository.load(playerId);
                    repository.loadPerkIds(playerId);
                    repository.loadPerkCosts(playerId);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[progression] preload failed for " + playerId, ex);
            }
            // 逓減の蓄積を共有DBから戻す。preload が失敗しても独立に読む(片方の障害で
            // もう片方まで落とすと、蓄積が消えた=逓減がリセットされた状態で遊べてしまう)。
            if (dailyExpPersistence != null) {
                dailyExpPersistence.load(playerId);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Evict cached progression on quit to bound memory; durable state stays in the delegate.
        // The generation bump inside evict also rejects any still-in-flight preload for this player.
        if (repository instanceof CachedProgressionRepository cached) {
            cached.evict(event.getPlayer().getUniqueId());
        }
        var quitting = event.getPlayer().getUniqueId();
        if (dailyExpPersistence != null) {
            // 保存してから捨てる。DB 書き込みなのでメインスレッドで待たない。
            // 逆順にすると保存対象が空になり「永続化したのに何も残らない」。
            // サーバ停止中(/stop の全員キック)は非同期タスクを投げられないので、
            // そのときは捨てずに残して onDisable の一括フラッシュへ任せる。
            if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                        () -> dailyExpPersistence.saveAndForget(quitting));
            }
        } else if (dailyExpDiminishing != null) {
            dailyExpDiminishing.forget(quitting);
        }
    }
}
