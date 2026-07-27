package com.trinityforge.listeners;

import com.trinityforge.progression.AchievementService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.Objects;

/** バニラ実績連動アチーブメント (2026-07-23-stat-gate-overhaul §6.2): 進捗達成イベントの薄い配線層。 */
public final class AchievementListener implements Listener {

    private final AchievementService service;

    public AchievementListener(AchievementService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        service.onAdvancementDone(event.getPlayer(), event.getAdvancement().getKey().toString());
    }
}
