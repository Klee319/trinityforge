package com.trinityforge.afk;

import com.trinityforge.config.domains.AfkConfig;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Objects;

/**
 * AFK 判定に使う「活動」信号を {@link AfkService} へ流す (2026-07-27)。
 *
 * <p><b>意図的に数えていないもの</b>: {@code PlayerInteractEvent}(素のクリック)と
 * {@code PlayerAnimationEvent}(腕振り)。オートクリッカー/マクロが無制限に生成できるのはこの2つで、
 * これらを活動に数えると「クリックしっぱなしで放置」が活動中と判定され、AFK対策そのものが
 * 無意味になる。逆に言えば、この機構が止められるのは<b>入力を伴わない放置</b>であって、
 * 視点を揺らし続けるような高度なマクロまでは止められない(それは別レイヤの課題)。
 *
 * <p>{@code PlayerMoveEvent} は毎tick飛ぶが、ここでの処理は座標比較と
 * {@link java.util.concurrent.ConcurrentHashMap#put} だけに抑えてある。
 * 位置も向きも変わっていないイベント(velocity のみの変化など)は早期 return する。
 */
public final class AfkActivityListener implements Listener {

    private final AfkService service;
    private final AfkConfig config;

    public AfkActivityListener(AfkService service, AfkConfig config) {
        this.service = Objects.requireNonNull(service, "service");
        this.config = Objects.requireNonNull(config, "config");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!config.enabled()) {
            return;
        }
        if (!changed(event.getFrom(), event.getTo())) {
            return;
        }
        service.touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            touch(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            touch(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        touch(event.getPlayer());
    }

    /** 参加直後は「たった今活動した」扱いにする(前回セッションの放置時間を持ち越さない)。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onRespawn(PlayerRespawnEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent event) {
        service.forget(event.getPlayer().getUniqueId());
    }

    private void touch(Player player) {
        if (config.enabled()) {
            service.touch(player);
        }
    }

    /** 位置または視線が変化したか。{@code to} が null のイベントは変化なし扱い。 */
    private static boolean changed(Location from, Location to) {
        if (to == null) {
            return false;
        }
        return from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ()
                || from.getYaw() != to.getYaw()
                || from.getPitch() != to.getPitch();
    }
}
