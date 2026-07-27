package com.trinityforge.listeners;

import com.trinityforge.mobs.DungeonGateService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces dungeon entry gates:
 * <ul>
 *   <li>cross-world teleports into a gated destination world (D2; portals included —
 *       {@code PlayerPortalEvent} shares {@code PlayerTeleportEvent}'s handler list),</li>
 *   <li>D3 topology: boundary crossings into a gated {@code region:} (区画/in-place dungeon),
 *       on both walking movement and same-/cross-world teleports.</li>
 * </ul>
 * Instanced-dungeon joins are gated via {@link DungeonGateService#checkEntry} from EliteMobs
 * (content-package alias), before the instance world even exists.
 */
public final class DungeonGateListener implements Listener {

    /** 区画境界に向かって歩き続けたときの拒否メッセージ最短間隔(ms)。移動自体は毎回キャンセルする。 */
    private static final long DENIAL_MESSAGE_INTERVAL_MS = 1500L;

    private final DungeonGateService gateService;
    private final Map<UUID, Long> lastDenialMessageAt = new ConcurrentHashMap<>();

    public DungeonGateListener(DungeonGateService gateService) {
        this.gateService = Objects.requireNonNull(gateService, "gateService");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        World from = event.getFrom().getWorld();
        World to = event.getTo() == null ? null : event.getTo().getWorld();
        if (to == null) {
            return;
        }
        if (!to.equals(from) && !gateService.checkEntry(event.getPlayer(), to.getName())) {
            event.setCancelled(true);
            return;
        }
        // 区画ゲート: 同一ワールド内テレポート/別ワールドの区画内直行どちらも外→内ならゲート。
        if (!gateService.checkRegionEntry(event.getPlayer(), event.getFrom(), event.getTo(), true)) {
            event.setCancelled(true);
        }
    }

    /**
     * D3 区画ダンジョン: 歩行での境界跨ぎをゲートする。ブロック座標が変わらない微小移動と
     * 区画ゲート未設定の構成では即リターンする(移動イベントのホットパス配慮)。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!gateService.hasRegionGates()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || sameBlock(from, to)) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastDenialMessageAt.get(uuid);
        boolean notify = last == null || now - last >= DENIAL_MESSAGE_INTERVAL_MS;
        if (!gateService.checkRegionEntry(event.getPlayer(), from, to, notify)) {
            if (notify) {
                lastDenialMessageAt.put(uuid, now);
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        lastDenialMessageAt.remove(event.getPlayer().getUniqueId());
    }

    private static boolean sameBlock(Location from, Location to) {
        return from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()
                && Objects.equals(from.getWorld(), to.getWorld());
    }
}
