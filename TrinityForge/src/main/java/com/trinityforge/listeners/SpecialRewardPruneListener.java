package com.trinityforge.listeners;

import com.trinityforge.progression.SpecialRewardPruner;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * {@code special-rewards.yml} から削除された報酬IDをプレイヤーのPDC保持分から掃除する
 * ({@link SpecialRewardPruner}) の発火点(2026-07-28)。
 *
 * <p>オフラインのプレイヤーのPDCは触れないため、参加時({@link #onJoin})が唯一の掃除機会。もう1つの
 * 発火点は {@code /trinityforge reload} 経由の {@link #pruneAllOnline()}(ItemRefreshListener の
 * {@code refreshAllOnlinePlayers} と同じ「reload後にオンライン全員へ即時反映する」形)。
 */
public final class SpecialRewardPruneListener implements Listener {

    private final Plugin plugin;
    private final SpecialRewardPruner pruner;

    public SpecialRewardPruneListener(Plugin plugin, SpecialRewardPruner pruner) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.pruner = Objects.requireNonNull(pruner, "pruner");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        pruneAndLog(event.getPlayer());
    }

    /** {@code /trinityforge reload} の後、config再読込が成功した場合にオンライン全員へ即座に適用する。 */
    public void pruneAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            pruneAndLog(player);
        }
    }

    private void pruneAndLog(Player player) {
        SpecialRewardPruner.PruneResult result = pruner.prune(player);
        if (result.isEmpty()) {
            return; // 何も消していないときは起動ログを汚さない(無音)。
        }
        Logger log = plugin.getLogger();
        if (!result.revokedGrants().isEmpty()) {
            log.info("[special-rewards] pruned orphaned grant(s) from " + player.getName()
                    + ": " + result.revokedGrants());
        }
        if (!result.clearedEquipped().isEmpty()) {
            log.info("[special-rewards] cleared orphaned equipped reward(s) for " + player.getName()
                    + ": " + result.clearedEquipped());
        }
    }
}
