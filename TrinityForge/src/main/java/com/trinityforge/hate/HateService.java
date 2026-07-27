package com.trinityforge.hate;

import com.trinityforge.config.domains.HateConfig;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the {@link HateTable} and its lifecycle: it projects live {@link HateConfig} into the
 * table's settings, schedules the periodic sweep, records threat from combat, and tears the
 * subsystem down on disable (gap C5).
 *
 * <p>The sweep runs on the async scheduler because it only touches the (synchronized) table and
 * a timestamp, never the Bukkit world; the eviction hooks in {@link HateListener} run on the
 * main thread. The table's monitor keeps the two safe.
 */
public final class HateService {

    /** Bukkit rejects timer periods below 1 tick; the schema floor is 20 but guard anyway. */
    private static final long MIN_SWEEP_PERIOD_TICKS = 1L;

    private final Plugin plugin;
    private final HateConfig config;
    private final HateTable table;
    private BukkitTask sweepTask;

    public HateService(Plugin plugin, HateConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.table = new HateTable(config.toSettings());
    }

    public HateTable table() {
        return table;
    }

    /**
     * Record threat from a player's hit on a mob. Threat is {@code damage * threat-per-damage}
     * (neutral 1:1 by default), stamped with the mob's world so a world unload can evict it.
     *
     * <p>The rate is read from {@code table.settings()} rather than {@link #config} directly
     * (task item 5): the table's settings snapshot is the single source of truth for every
     * live-combat read, kept in lockstep with caps/decay/TTL and swapped atomically on
     * {@link #applyConfig}, so this path can never disagree with what the table itself is using.
     */
    public void recordDamage(Entity mob, Player attacker, double damage) {
        if (mob == null || attacker == null || !(damage > 0.0)) {
            return;
        }
        double amount = damage * table.settings().threatPerDamage();
        table.addThreat(mob.getUniqueId(), attacker.getUniqueId(),
                mob.getWorld().getUID(), amount, System.currentTimeMillis());
    }

    public Optional<UUID> topAttacker(UUID mobId) {
        return table.topAttacker(mobId);
    }

    /** Start the periodic sweep (call once after enable). */
    public void start() {
        restartSweep();
    }

    /** Re-read caps/decay/TTL/cadence from config after a reload. */
    public void applyConfig() {
        table.applySettings(config.toSettings());
        restartSweep();
    }

    /** Cancel the sweep and drop all tracked data (plugin disable). */
    public synchronized void shutdown() {
        cancelSweep();
        table.clear();
    }

    private synchronized void restartSweep() {
        cancelSweep();
        long period = Math.max(MIN_SWEEP_PERIOD_TICKS, config.sweepIntervalTicks());
        this.sweepTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, () -> table.sweep(System.currentTimeMillis()), period, period);
    }

    private void cancelSweep() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }
}
