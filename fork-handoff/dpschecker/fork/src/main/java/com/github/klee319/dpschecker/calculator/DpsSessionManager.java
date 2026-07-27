package com.github.klee319.dpschecker.calculator;

import com.github.klee319.dpschecker.dummy.DamageRecord;
import com.github.klee319.dpschecker.dummy.DummyEntity;
import com.github.klee319.dpschecker.dummy.DummyManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player chat-based DPS measurement sessions started via /dps on.
 * Same-name dummies are aggregated into a single session metric.
 */
public class DpsSessionManager {

    private static final long TIMEOUT_MILLIS = 60_000L;
    private static final long REPORT_INTERVAL_MILLIS = 1_000L;

    private final JavaPlugin plugin;
    private final DummyManager dummyManager;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    public DpsSessionManager(JavaPlugin plugin, DummyManager dummyManager) {
        this.plugin = plugin;
        this.dummyManager = dummyManager;
    }

    public void start() {
        if (tickTask != null) return;
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        sessions.clear();
    }

    public boolean hasSession(UUID playerUuid) {
        return sessions.containsKey(playerUuid);
    }

    public boolean startSession(Player player, String dummyName) {
        UUID id = player.getUniqueId();
        if (sessions.containsKey(id)) return false;

        List<DummyEntity> matched = dummyManager.getDummiesByName(dummyName);
        if (matched.isEmpty()) {
            player.sendMessage(prefix().append(Component.text(
                    "「" + dummyName + "」という名前のカカシが見つかりません。", NamedTextColor.RED)));
            return false;
        }

        Session session = new Session(id, dummyName);
        sessions.put(id, session);
        scheduleCountdown(player, session);
        return true;
    }

    public boolean stopSession(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return false;

        if (session.state == State.ACTIVE) {
            sendFinalReport(player, session, "計測終了");
        } else {
            player.sendMessage(prefix().append(Component.text(
                    "カウントダウン中の計測をキャンセルしました。", NamedTextColor.YELLOW)));
        }
        return true;
    }

    /**
     * Called by DamageListener whenever a dummy takes damage. Forwarded into any
     * active sessions that target the dummy's name.
     */
    public void onDamage(DummyEntity dummy, DamageRecord record) {
        if (sessions.isEmpty()) return;
        for (Session session : sessions.values()) {
            if (session.state != State.ACTIVE) continue;
            if (!session.dummyName.equals(dummy.getName())) continue;
            if (record.timestamp() < session.startTimeMillis) continue;
            session.totalDamage += record.finalDamage();
            session.totalHits++;
            session.lastDamageTimeMillis = record.timestamp();
        }
    }

    private void scheduleCountdown(Player player, Session session) {
        // 3 -> 2 -> 1 -> START at 1-second intervals.
        Bukkit.getScheduler().runTask(plugin, () -> sendCountdown(player, 3));
        Bukkit.getScheduler().runTaskLater(plugin, () -> sendCountdown(player, 2), 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> sendCountdown(player, 1), 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> activateSession(player, session), 60L);
    }

    private void sendCountdown(Player player, int n) {
        if (!sessions.containsKey(player.getUniqueId())) return;
        if (!player.isOnline()) return;
        player.sendMessage(prefix().append(Component.text(
                String.valueOf(n), NamedTextColor.YELLOW)));
    }

    private void activateSession(Player player, Session session) {
        if (!sessions.containsKey(player.getUniqueId())) return;
        if (!player.isOnline()) {
            sessions.remove(player.getUniqueId());
            return;
        }
        long now = System.currentTimeMillis();
        session.state = State.ACTIVE;
        session.startTimeMillis = now;
        session.lastDamageTimeMillis = now;
        session.lastReportTimeMillis = now;
        int count = dummyManager.getDummiesByName(session.dummyName).size();
        player.sendMessage(prefix().append(Component.text("測定スタート ", NamedTextColor.GREEN))
                .append(Component.text("(対象: ", NamedTextColor.GRAY))
                .append(Component.text(session.dummyName, NamedTextColor.WHITE))
                .append(Component.text(" / " + count + "体)", NamedTextColor.GRAY)));
    }

    private void tick() {
        if (sessions.isEmpty()) return;
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> entry = it.next();
            Session session = entry.getValue();
            if (session.state != State.ACTIVE) continue;

            Player player = Bukkit.getPlayer(session.playerUuid);
            if (player == null || !player.isOnline()) {
                it.remove();
                continue;
            }

            if (now - session.lastDamageTimeMillis >= TIMEOUT_MILLIS) {
                sendFinalReport(player, session, "60秒間ダメージなしのため計測終了");
                it.remove();
                continue;
            }

            if (session.totalDamage > 0
                    && now - session.lastReportTimeMillis >= REPORT_INTERVAL_MILLIS) {
                sendDpsUpdate(player, session, now);
                session.lastReportTimeMillis = now;
            }
        }
    }

    private void sendDpsUpdate(Player player, Session session, long now) {
        double elapsed = Math.max(0.001, (now - session.startTimeMillis) / 1000.0);
        double totalDps = session.totalDamage / elapsed;
        int count = Math.max(1, dummyManager.getDummiesByName(session.dummyName).size());
        double avgDps = totalDps / count;

        Component msg = prefix()
                .append(Component.text(session.dummyName, NamedTextColor.WHITE))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format("経過%.1fs", elapsed), NamedTextColor.GRAY))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format("合計DPS %.2f", totalDps), NamedTextColor.RED));

        if (count > 1) {
            msg = msg.append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(String.format("%d体平均 %.2f", count, avgDps),
                            NamedTextColor.GOLD));
        }
        player.sendMessage(msg);
    }

    private void sendFinalReport(Player player, Session session, String reason) {
        long now = System.currentTimeMillis();
        double elapsed = Math.max(0.001, (now - session.startTimeMillis) / 1000.0);
        double totalDps = session.totalDamage / elapsed;
        int count = Math.max(1, dummyManager.getDummiesByName(session.dummyName).size());
        double avgDps = totalDps / count;

        player.sendMessage(prefix().append(Component.text(reason, NamedTextColor.GOLD)));
        player.sendMessage(prefix()
                .append(Component.text("対象: ", NamedTextColor.GRAY))
                .append(Component.text(session.dummyName + " (" + count + "体)", NamedTextColor.WHITE)));
        player.sendMessage(prefix()
                .append(Component.text(String.format("経過時間: %.2fs", elapsed), NamedTextColor.GRAY))
                .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format("総ダメージ: %.2f", session.totalDamage),
                        NamedTextColor.WHITE))
                .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format("ヒット数: %d", session.totalHits),
                        NamedTextColor.WHITE)));
        Component finalLine = prefix()
                .append(Component.text(String.format("合計DPS: %.2f", totalDps), NamedTextColor.RED));
        if (count > 1) {
            finalLine = finalLine.append(Component.text(
                    String.format(" / 1体平均DPS: %.2f", avgDps), NamedTextColor.GOLD));
        }
        player.sendMessage(finalLine);
    }

    private Component prefix() {
        return Component.text("[DPSChecker] ", NamedTextColor.GOLD);
    }

    private enum State { COUNTDOWN, ACTIVE }

    private static final class Session {
        final UUID playerUuid;
        final String dummyName;
        State state = State.COUNTDOWN;
        long startTimeMillis;
        long lastDamageTimeMillis;
        long lastReportTimeMillis;
        double totalDamage;
        int totalHits;

        Session(UUID playerUuid, String dummyName) {
            this.playerUuid = playerUuid;
            this.dummyName = dummyName;
        }
    }
}
