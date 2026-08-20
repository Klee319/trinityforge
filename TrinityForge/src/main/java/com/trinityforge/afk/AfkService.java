package com.trinityforge.afk;

import com.trinityforge.config.domains.AfkConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    /**
     * タブ表示サフィックスを<b>この機構が実際に付けた</b>プレイヤー。設定フラグを見て消すのではなく
     * 「付けた実績」を見て消すためにある — {@code tab-suffix} を AFK 中に false へ切り替えて reload
     * すると、フラグだけを見る実装では [AFK] が張り付いたまま残ってしまう。
     */
    private final Set<UUID> tabSuffixApplied = ConcurrentHashMap.newKeySet();
    /**
     * 予告カウントダウンで「タイトルを出し終えた段階」。同じ段階のあいだタイトルを出し直さないためだけに
     * 持つ（毎秒タイトルを再送すると画面が点滅して読めない）。アクションバーは毎秒更新する。
     */
    private final Map<UUID, WarnStage> warnStages = new ConcurrentHashMap<>();
    private BukkitTask task;
    private BukkitTask warnTask;

    /** 予告カウントダウンの対象となる「次に起きること」。 */
    enum WarnStage {
        /** まだ AFK ではなく、AFK 判定までの残りが予告窓に入った。 */
        BEFORE_AFK,
        /** すでに AFK で、自動キックまでの残りが予告窓に入った。 */
        BEFORE_KICK
    }

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
        // 予告は判定タイマーとは別に毎秒回す。判定間隔(既定40tick=2秒)に相乗りさせると
        // カウントダウンが 30,28,26... と飛んで「カウントダウン」に見えないため。
        if (config.warnBeforeSeconds() > 0) {
            this.warnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::warnTick, 20L, 20L);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (warnTask != null) {
            warnTask.cancel();
            warnTask = null;
        }
        warnStages.clear();
    }

    /** ログアウト時に状態を捨てる(再ログイン時は活動直後として扱われる)。 */
    public void forget(UUID playerId) {
        lastActivityMillis.remove(playerId);
        afkFlags.remove(playerId);
        warnStages.remove(playerId);
        // 再入場時は新しい Player でタブ名も既定へ戻るので、付けた実績も一緒に捨てる。
        tabSuffixApplied.remove(playerId);
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
        if (warnStages.remove(id) != null) {
            // 予告中に動いた = 予告は用済み。残ったカウントダウンを即座に消す
            // (放っておいても数秒で薄れるが、「あと5秒」が残ったままなのは動いた側に伝わらない)。
            clearActionBar(player);
        }
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

    /**
     * 予告カウントダウン(2026-08-18 ユーザー報告「AFK が現状訪れるので title 等でカウントダウンか
     * 通知を表示してほしい」)。
     *
     * <p><b>判定タイマーに相乗りさせない理由</b>: 既定の {@code check-interval-ticks} は 40(=2秒)なので、
     * そこで出すと「30, 28, 26, ...」と飛んでカウントダウンに見えない。ここは常に毎秒回す。
     *
     * <p>タイトルは<b>段階が変わった1回だけ</b>出す。毎秒出し直すと fadeIn がかかり直して画面が
     * 点滅し、かえって読めなくなる。毎秒更新するのはアクションバーの数字のほうだけ。
     */
    private void warnTick() {
        long warnMillis = config.warnBeforeSeconds() * 1000L;
        if (!config.enabled() || warnMillis <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long idleMillis = config.idleSeconds() * 1000L;
        long kickMillis = config.kickAfterSeconds() * 1000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (isExempt(player)) {
                warnStages.remove(id);
                continue;
            }
            Long last = lastActivityMillis.get(id);
            if (last == null) {
                continue;
            }
            WarnState state = warnStateFor(Boolean.TRUE.equals(afkFlags.get(id)),
                    now - last, idleMillis, kickMillis, warnMillis);
            if (state == null) {
                if (warnStages.remove(id) != null) {
                    clearActionBar(player);
                }
                continue;
            }
            if (warnStages.put(id, state.stage()) != state.stage() && config.warnTitle()) {
                showWarnTitle(player, state);
            }
            player.sendActionBar(actionBarFor(state));
        }
    }

    /** 予告の1コマ。{@code stage} は「次に何が起きるか」、{@code remainingSeconds} はそこまでの残り秒。 */
    record WarnState(WarnStage stage, int remainingSeconds) {
    }

    /**
     * 予告を出すべきかと、その残り秒を決める<b>純関数</b>(表示から切り離してあるのは検証のため)。
     * 出さないなら {@code null}。
     *
     * <p>AFK 前は「AFK 判定まで」、AFK 中は「自動キックまで」を数える。
     * {@code kickMillis <= 0}(キックしない設定)なら AFK 中は何も予告しない ——
     * 何も起きないのにカウントダウンを出しても意味が無い。
     */
    static WarnState warnStateFor(boolean afk, long elapsedMillis,
                                  long idleMillis, long kickMillis, long warnMillis) {
        if (warnMillis <= 0) {
            return null;
        }
        long deadline = afk ? kickMillis : idleMillis;
        if (afk && kickMillis <= 0) {
            return null;
        }
        long remaining = deadline - elapsedMillis;
        if (remaining <= 0 || remaining > warnMillis) {
            return null;
        }
        // 切り上げ。残り 0.4 秒を「あと0秒」と出すと、消えるまでのあいだ嘘の表示になる。
        int seconds = (int) Math.max(1L, (remaining + 999L) / 1000L);
        return new WarnState(afk ? WarnStage.BEFORE_KICK : WarnStage.BEFORE_AFK, seconds);
    }

    private static Component actionBarFor(WarnState state) {
        // 残り5秒以下は赤へ。色だけで「もう本当に直前」と分かるようにする。
        NamedTextColor color = state.remainingSeconds() <= 5 ? NamedTextColor.RED : NamedTextColor.YELLOW;
        String label = state.stage() == WarnStage.BEFORE_KICK ? "切断まで " : "放置判定まで ";
        return Component.text(label, color)
                .append(Component.text(state.remainingSeconds() + " 秒", color))
                .append(Component.text(" — 動けば解除されます", NamedTextColor.GRAY));
    }

    private void showWarnTitle(Player player, WarnState state) {
        Component main = state.stage() == WarnStage.BEFORE_KICK
                ? Component.text("まもなく切断されます", NamedTextColor.RED)
                : Component.text("まもなく放置判定になります", NamedTextColor.YELLOW);
        Component sub = state.stage() == WarnStage.BEFORE_KICK
                ? Component.text("何か操作すれば切断されません", NamedTextColor.GRAY)
                : Component.text("この間の経験値・追加ドロップ・自動換金は入りません", NamedTextColor.GRAY);
        // fadeOut を短くしてアクションバーのカウントダウンへ視線を渡す。
        player.showTitle(Title.title(main, sub,
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))));
    }

    /**
     * 出しっぱなしのカウントダウンを消す。{@code touch} は {@code AsyncChatEvent}(非同期)からも
     * 来るので、{@link #applyTabSuffix} と同じくここでメインスレッドへ寄せる。
     */
    private void clearActionBar(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> clearActionBar(player));
            }
            return;
        }
        player.sendActionBar(Component.empty());
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
     *
     * <p><b>必ずメインスレッドで実行する。</b>AFK 解除は {@code AsyncChatEvent}(非同期)からも
     * 呼ばれるが、{@code Player#playerListName} は async-safe ではない。呼び出し元に判断を
     * 委ねるとホップ漏れが再発するので、ここで一括して吸収する。
     */
    private void applyTabSuffix(Player player, boolean afk) {
        if (!Bukkit.isPrimaryThread()) {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> applyTabSuffix(player, afk));
            }
            return;
        }
        UUID id = player.getUniqueId();
        // 付けるときだけ設定を見る。外すときは「自分が付けたか」だけで判断する(上の tabSuffixApplied 参照)。
        if (afk && !config.tabSuffix()) {
            return;
        }
        if (!afk && !tabSuffixApplied.remove(id)) {
            return;
        }
        try {
            if (!afk) {
                player.playerListName(null);
                return;
            }
            Component suffix = MiniMessage.miniMessage().deserialize(config.tabSuffixText());
            player.playerListName(Component.text(player.getName()).append(suffix));
            tabSuffixApplied.add(id);
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
