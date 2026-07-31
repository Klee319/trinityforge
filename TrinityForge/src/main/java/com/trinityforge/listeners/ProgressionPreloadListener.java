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

    public ProgressionPreloadListener(Plugin plugin, ProgressionRepository repository) {
        this(plugin, repository, null);
    }

    public ProgressionPreloadListener(Plugin plugin, ProgressionRepository repository,
                                      com.trinityforge.progression.DailyExpDiminishing dailyExpDiminishing) {
        this.dailyExpDiminishing = dailyExpDiminishing;
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
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Evict cached progression on quit to bound memory; durable state stays in the delegate.
        // The generation bump inside evict also rejects any still-in-flight preload for this player.
        if (repository instanceof CachedProgressionRepository cached) {
            cached.evict(event.getPlayer().getUniqueId());
        }
        if (dailyExpDiminishing != null) {
            dailyExpDiminishing.forget(event.getPlayer().getUniqueId());
        }
    }
}
