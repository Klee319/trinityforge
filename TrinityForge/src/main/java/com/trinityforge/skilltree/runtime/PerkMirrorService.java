package com.trinityforge.skilltree.runtime;

import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.repository.LoadResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps each online player's PDC held-perk mirror ({@code com.trinityforge.pdc.PlayerData}, UNLOCK 2.1) in
 * step with the live ValhallaMMO unlocked-perk set from {@link SkillPerkStatSource}. The mirror is what the
 * ArsPaper integration reads on the cast/craft/ritual hot path ({@code PlayerData.of(player).heldPerks()}), so
 * this service is the single writer that makes those unlock gates actually see a player's skill-tree progress —
 * without it {@code heldPerks()} is always empty and every gate is inert.
 *
 * <p>Sync strategy (K decision 2026-07-17): join + light periodic. A join sync gives an immediate mirror at
 * login; a repeating main-thread task backfills every {@link #DEFAULT_SYNC_INTERVAL_TICKS} ticks so a perk
 * unlocked mid-session (ValhallaMMO fires no unlock event) is reflected within one interval. The periodic read
 * rides {@code ValhallaSkillPerkStatSource}'s per-player TTL cache, and {@link PerkMirror#needsWrite} skips the
 * PDC write whenever nothing changed, so steady-state cost is negligible.
 *
 * <p>The mirror copies a successful source result verbatim, including an empty set. A failed read leaves the
 * existing PDC value untouched as last-known-good; otherwise a transient database outage would revoke every
 * mirrored gate until the next successful interval. Both reads and the PDC write run on the main thread, so the
 * task is a plain {@code runTaskTimer}.
 */
public final class PerkMirrorService {

    /** 100 ticks = 5s at 20 TPS: fast enough that a just-unlocked gate opens promptly, cheap enough to poll. */
    public static final long DEFAULT_SYNC_INTERVAL_TICKS = 100L;

    private final Plugin plugin;
    private final SkillPerkStatSource source;
    private final long intervalTicks;
    private BukkitTask task;

    public PerkMirrorService(Plugin plugin, SkillPerkStatSource source, long intervalTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.source = Objects.requireNonNull(source, "source");
        if (intervalTicks <= 0) {
            throw new IllegalArgumentException("intervalTicks must be positive: " + intervalTicks);
        }
        this.intervalTicks = intervalTicks;
    }

    /**
     * Reconciles one player's PDC held-perk mirror against the live unlocked-perk set, writing only when it
     * changed. Main thread only (ValhallaMMO reflection + PDC write).
     */
    public void sync(Player player) {
        Objects.requireNonNull(player, "player");
        LoadResult<Set<String>> load = source.loadUnlockedPerkIds(player.getUniqueId());
        if (load.isFailed()) {
            return;
        }
        Set<String> unlocked = load.orElseGet(Set::of);
        List<String> canonical = PerkMirror.canonical(unlocked);
        PlayerData data = PlayerData.of(player);
        if (PerkMirror.needsWrite(data.heldPerks(), canonical)) {
            data.setHeldPerks(canonical);
        }
    }

    /** Starts the repeating backfill task; idempotent. */
    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::syncAll, intervalTicks, intervalTicks);
    }

    private void syncAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sync(player);
        }
    }

    /** Cancels the backfill task so a disable/hot-reload leaks nothing; idempotent. */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
