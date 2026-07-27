package com.trinityforge.afk;

import com.trinityforge.config.domains.AfkConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AFK(離席)状態の一次情報 (2026-07-27)。最終活動時刻を持ち、しきい値超過で AFK 判定を立て、
 * 設定によりタブ表示・通知・自動キックを行う。
 *
 * <p><b>「活動」の定義は {@link AfkActivityListener} 側にある。</b>この機構の肝はそこで、
 * 素のクリック/腕振りを活動に数えない点にある(オートクリッカーが無限に出せる信号を活動に
 * 数えると、この機構は丸ごと無意味になる)。
 *
 * <p>報酬停止は「AFK かどうか」を各所から問い合わせる形で実現する({@link #isAfk(Player)} /
 * {@link #isSuppressed(UUID)})。停止側に判定ロジックを分散させないのは、AFK の定義がここ1箇所に
 * 閉じていないと「経路ごとに微妙に違うAFK」が生まれるため。
 *
 * <p>免除({@code exempt-permission})は<b>判定そのものを行わない</b>。免除者は AFK にならず、
 * したがって報酬停止もキックもされない。
 */
public final class AfkService {

    private final Plugin plugin;
    private final AfkConfig config;
    private final Map<UUID, Long> lastActivityMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> afkFlags = new ConcurrentHashMap<>();
    private BukkitTask task;

    public AfkService(Plugin plugin, AfkConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** 判定タイマーを(再)開始する。{@code /trinityforge reload} からも呼べるよう冪等。 */
    public void start() {
        stop();
        if (!config.enabled()) {
            return;
        }
        long interval = Math.max(20L, config.checkIntervalTicks());
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** ログアウト時に状態を捨てる(再ログイン時は活動直後として扱われる)。 */
    public void forget(UUID playerId) {
        lastActivityMillis.remove(playerId);
        afkFlags.remove(playerId);
    }

    /**
     * 活動を記録する。AFK だったなら即座に解除して復帰通知を出す
     * (次のタイマー tick を待たない — 復帰した直後の1回の行動が無報酬になるのを避けるため)。
     */
    public void touch(Player player) {
        if (player == null || !config.enabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        lastActivityMillis.put(id, System.currentTimeMillis());
        if (Boolean.TRUE.equals(afkFlags.get(id))) {
            clearAfk(player);
        }
    }

    /** そのプレイヤーが現在 AFK か。免除者は常に {@code false}。 */
    public boolean isAfk(Player player) {
        if (player == null || !config.enabled() || isExempt(player)) {
            return false;
        }
        return Boolean.TRUE.equals(afkFlags.get(player.getUniqueId()));
    }

    /** UUID 版。オフライン/未知のIDは {@code false}(存在しない相手を AFK 扱いにしない)。 */
    public boolean isAfk(UUID playerId) {
        if (playerId == null || !config.enabled()) {
            return false;
        }
        Player player = Bukkit.getPlayer(playerId);
        return player != null && isAfk(player);
    }

    /**
     * 報酬停止の共通述語。停止の可否は呼び出し側が {@code AfkConfig#suppress*} で判断し、
     * ここは「AFK か」だけを答える。
     */
    public boolean isSuppressed(UUID playerId) {
        return isAfk(playerId);
    }

    private void tick() {
        if (!config.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long idleMillis = config.idleSeconds() * 1000L;
        long kickMillis = config.kickAfterSeconds() * 1000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (isExempt(player)) {
                // 免除者に古いAFKフラグが残っていると、権限を付けた直後も停止が続いてしまう。
                if (Boolean.TRUE.equals(afkFlags.get(id))) {
                    clearAfk(player);
                }
                continue;
            }
            long last = lastActivityMillis.computeIfAbsent(id, key -> now);
            long elapsed = now - last;
            if (elapsed < idleMillis) {
                continue;
            }
            if (!Boolean.TRUE.equals(afkFlags.get(id))) {
                markAfk(player);
            }
            if (kickMillis > 0 && elapsed >= kickMillis) {
                kick(player);
            }
        }
    }

    private void markAfk(Player player) {
        afkFlags.put(player.getUniqueId(), Boolean.TRUE);
        if (config.notifyPlayer()) {
            player.sendMessage(Component.text("放置状態と判定しました。", NamedTextColor.YELLOW)
                    .append(Component.text(" この間の経験値・追加ドロップ・自動換金は入りません。",
                            NamedTextColor.GRAY)));
        }
        applyTabSuffix(player, true);
    }

    private void clearAfk(Player player) {
        afkFlags.remove(player.getUniqueId());
        if (config.notifyPlayer()) {
            player.sendMessage(Component.text("放置状態を解除しました。", NamedTextColor.GREEN));
        }
        applyTabSuffix(player, false);
    }

    /**
     * タブリスト名の付け替え。{@code playerListName(null)} で既定(スコアボードのチーム装飾等を含む
     * サーバ既定の表示名)へ戻るので、解除時は自前で名前を作り直さない — 作り直すと他プラグインが
     * 付けた装飾を踏み潰す。
     */
    private void applyTabSuffix(Player player, boolean afk) {
        if (!config.tabSuffix()) {
            return;
        }
        try {
            if (!afk) {
                player.playerListName(null);
                return;
            }
            Component suffix = MiniMessage.miniMessage().deserialize(config.tabSuffixText());
            player.playerListName(Component.text(player.getName()).append(suffix));
        } catch (RuntimeException ex) {
            // 表示の飾りでゲームループを壊さない(MiniMessage記法のtypo等)。
            plugin.getLogger().warning("[afk] tab-suffix の適用に失敗しました: " + ex.getMessage());
        }
    }

    private void kick(Player player) {
        forget(player.getUniqueId());
        try {
            player.kick(MiniMessage.miniMessage().deserialize(config.kickMessage()));
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[afk] kick-message の解釈に失敗したため既定文言でキックします: "
                    + ex.getMessage());
            player.kick(Component.text("長時間の放置により切断しました。", NamedTextColor.YELLOW));
        }
    }

    private boolean isExempt(Player player) {
        String permission = config.exemptPermission();
        return !permission.isEmpty() && player.hasPermission(permission);
    }
}
