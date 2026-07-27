package com.trinityforge.listeners;

import com.trinityforge.skilltree.runtime.PerkMirrorService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Objects;

/**
 * Seeds a player's PDC held-perk mirror at login so an unlock gate is correct immediately, rather than waiting
 * up to one {@link PerkMirrorService#DEFAULT_SYNC_INTERVAL_TICKS} interval for the periodic backfill. Runs at
 * MONITOR so ValhallaMMO's own join handling has already restored the player's PowerProfile; if the profile is
 * not yet readable the read degrades to empty and the next periodic sync backfills it (the service tolerates a
 * transient empty by design).
 */
public final class PerkMirrorListener implements Listener {

    private final PerkMirrorService perkMirrorService;

    public PerkMirrorListener(PerkMirrorService perkMirrorService) {
        this.perkMirrorService = Objects.requireNonNull(perkMirrorService, "perkMirrorService");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        perkMirrorService.sync(event.getPlayer());
    }
}
