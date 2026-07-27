package com.trinityforge.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Objects;

/**
 * Blocks player-facing EliteMobs / AdventurersGuild command aliases while TrinityForge owns
 * progression, shops, and combat. Ops with {@code trinityforge.elitemobs.commands} (or admin) may
 * still use them for dungeon tooling.
 */
public final class EliteMobsCommandGateListener implements Listener {

    private static final Component DENIED = Component.text(
            "EliteMobs のプレイヤーコマンドは無効です。進行・ステータスは /skills を使ってください。",
            NamedTextColor.RED);

    private static final String BYPASS = "trinityforge.elitemobs.commands";

    public EliteMobsCommandGateListener() {
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        Objects.requireNonNull(player, "player");
        if (player.hasPermission(BYPASS) || player.hasPermission("trinityforge.admin")) {
            return;
        }
        String raw = event.getMessage();
        if (raw == null || raw.isBlank() || raw.charAt(0) != '/') {
            return;
        }
        String body = raw.substring(1).trim().toLowerCase(Locale.ROOT);
        int space = body.indexOf(' ');
        String label = space < 0 ? body : body.substring(0, space);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (isBlockedLabel(label)) {
            event.setCancelled(true);
            player.sendMessage(DENIED);
        }
    }

    static boolean isBlockedLabel(String label) {
        return switch (label) {
            case "em", "elitemobs", "ag", "adventurersguild", "adventurers_guild" -> true;
            default -> false;
        };
    }
}
