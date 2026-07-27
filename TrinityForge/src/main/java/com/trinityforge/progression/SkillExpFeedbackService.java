package com.trinityforge.progression;

import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.core.XpTransitionService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * スキルEXP獲得時のプレイヤー表示 (S5) とレベルアップ通知 (S6)。
 *
 * <p><b>S5</b>: 既定はボスバーで「スキル名 Lv{level}  {現EXP}/{次レベルEXP}  (+{獲得量})」を表示し、
 * 進捗バーは現レベル内EXP比。設定 {@code exp-display.mode: actionbar} で「獲得量のみ」をアクションバー表示に切替。
 * ボスバーは {@code exp-display.bossbar-seconds} 秒後に自動で隠す。
 *
 * <p><b>S6</b>: レベルアップ時に通知音 + チャット、{@code level-up.title-every-levels}(既定10)の倍数レベル到達で
 * タイトル表示。
 *
 * <p>{@link NativeExperienceDispatcher} は非同期で本フィードバックを呼ぶため、全ての Bukkit 操作は
 * {@link #onExpGranted} 内でメインスレッドへ再ディスパッチしてから行う。
 *
 * <p><b>B3 (2026-07-25 バグ報告)</b>: 1回の行動で複数スキルのEXPが同時に入る場合(例: 採掘で MINING の
 * EXPに加え、ツール耐久消費経由で SMITHING のEXPも入る)、スキルごとに独立したボスバーを表示する
 * ({@code プレイヤーUUID + skillId} をキーに管理、Minecraftのボスバーは縦に積み重なって表示される)。
 * 同時表示数は {@link SkillExpConfig#maxConcurrentBossBars()} で上限を設け、超過時は最も古く追加された
 * スキルのボスバーから閉じる(FIFO)。プレイヤーのログアウト時は {@link #onQuit} でそのプレイヤーの
 * 全ボスバー・全タイマーを確実に破棄する(リーク防止)。
 */
public final class SkillExpFeedbackService implements SkillExpFeedback, Listener {

    private final Plugin plugin;
    private final SkillExpConfig config;
    private final NativeSkillCatalog catalog;
    private final Function<String, String> skillDisplayName;
    /** プレイヤーごとのスキル別ボスバー管理。挿入順を保つ({@link LinkedHashMap})のでFIFO失効に使える。 */
    private final Map<UUID, PlayerBossBars> playerBossBars = new ConcurrentHashMap<>();

    /** 1プレイヤー分のスキル別ボスバー/非表示タイマー。挿入順=表示を開始した順(FIFO失効の基準)。 */
    private static final class PlayerBossBars {
        private final Map<String, BossBar> bars = new LinkedHashMap<>();
        private final Map<String, BukkitTask> hideTasks = new HashMap<>();
    }

    public SkillExpFeedbackService(Plugin plugin, SkillExpConfig config, NativeSkillCatalog catalog,
                                   Function<String, String> skillDisplayName) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.skillDisplayName = Objects.requireNonNull(skillDisplayName, "skillDisplayName");
    }

    @Override
    public void onExpGranted(UUID playerId, String skillId, double amount,
                             NativeProgressionService.GrantResult result) {
        // drain() は非同期スレッド。Bukkit API 操作前に必ずメインスレッドへ。
        Bukkit.getScheduler().runTask(plugin, () -> show(playerId, skillId, amount, result));
    }

    private void show(UUID playerId, String skillId, double amount,
                      NativeProgressionService.GrantResult result) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        String name = skillDisplayName.apply(skillId);
        long gain = Math.round(amount);
        if (gain <= 0) {
            gain = amount > 0 ? 1 : 0; // 端数でも「獲得した」ことは示す
        }
        if (config.expDisplayActionBarOnly()) {
            player.sendActionBar(Component.text("+" + gain + " " + name + " EXP", NamedTextColor.GREEN));
        } else {
            showBossBar(player, skillId, name, result.after(), gain);
        }
        if (result.levelsChanged() > 0) {
            onLevelUp(player, name, result.after().level());
        }
    }

    /**
     * B3: スキルIDごとに独立したボスバーを表示する。既に同じスキルのバーが出ていればその場で更新
     * (挿入順は変えない=そのバー自身の表示継続がFIFO順で不利にならない)。新規スキルで上限
     * ({@link SkillExpConfig#maxConcurrentBossBars()}) に達している場合は、最も古く追加されたスキルの
     * バーを1本閉じてから追加する。
     */
    private void showBossBar(Player player, String skillId, String name, SkillProgress after, long gain) {
        int level = after.level();
        float progress = progressWithin(skillId, after);
        Component title = Component.text(name + " Lv" + level + "  ", NamedTextColor.AQUA)
                .append(Component.text(fmt(after.residualExp()) + "/" + fmt(spanForLevel(skillId, level)),
                        NamedTextColor.WHITE))
                .append(Component.text("  (+" + gain + ")", NamedTextColor.GREEN));

        UUID id = player.getUniqueId();
        PlayerBossBars pb = playerBossBars.computeIfAbsent(id, k -> new PlayerBossBars());

        BossBar bar = pb.bars.get(skillId);
        if (bar == null) {
            evictOldestIfAtCapacity(player, pb);
            bar = BossBar.bossBar(title, progress, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
            pb.bars.put(skillId, bar);
        } else {
            bar.name(title);
            bar.progress(progress);
        }
        player.showBossBar(bar);

        BukkitTask prev = pb.hideTasks.remove(skillId);
        if (prev != null) {
            prev.cancel();
        }
        long ticks = Math.max(10L, Math.round(config.expBarSeconds() * 20.0));
        BukkitTask hide = Bukkit.getScheduler().runTaskLater(plugin, () -> hideOne(player, id, skillId), ticks);
        pb.hideTasks.put(skillId, hide);
    }

    /** 上限到達時、最も古く追加された(=挿入順が先頭の)スキルのボスバーを1本閉じる(FIFO失効, B3)。 */
    private void evictOldestIfAtCapacity(Player player, PlayerBossBars pb) {
        int cap = Math.max(1, config.maxConcurrentBossBars());
        if (pb.bars.size() < cap) {
            return;
        }
        Iterator<Map.Entry<String, BossBar>> it = pb.bars.entrySet().iterator();
        if (!it.hasNext()) {
            return;
        }
        Map.Entry<String, BossBar> oldest = it.next();
        player.hideBossBar(oldest.getValue());
        it.remove();
        BukkitTask oldestTask = pb.hideTasks.remove(oldest.getKey());
        if (oldestTask != null) {
            oldestTask.cancel();
        }
    }

    /** 単一スキルのボスバーを隠す(自動非表示タイマーのコールバック)。プレイヤーが既にオフラインでも安全。 */
    private void hideOne(Player player, UUID playerId, String skillId) {
        PlayerBossBars pb = playerBossBars.get(playerId);
        if (pb == null) {
            return;
        }
        BossBar b = pb.bars.remove(skillId);
        if (b != null && player.isOnline()) {
            player.hideBossBar(b);
        }
        pb.hideTasks.remove(skillId);
    }

    /**
     * B3 リーク防止: ログアウト時にそのプレイヤーの全スキルボスバー・全非表示タイマーを確実に破棄する。
     * (前回レビューで LOW「PlayerQuit即掃除なし」として記録されていた欠落 — 本サービスは元々
     * {@link Listener} を実装しておらず、どのイベントにも登録されていなかった。)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        PlayerBossBars pb = playerBossBars.remove(event.getPlayer().getUniqueId());
        if (pb == null) {
            return;
        }
        for (BukkitTask task : pb.hideTasks.values()) {
            task.cancel();
        }
        for (BossBar bar : pb.bars.values()) {
            event.getPlayer().hideBossBar(bar);
        }
    }

    private void onLevelUp(Player player, String name, int level) {
        if (config.levelUpSoundEnabled()) {
            Key key = soundKey(config.levelUpSound());
            if (key != null) {
                player.playSound(Sound.sound(key, Sound.Source.PLAYER, 1.0f, 1.0f));
            }
        }
        if (config.levelUpChat()) {
            player.sendMessage(Component.text("★ " + name + " が Lv" + level + " に上がった！",
                    NamedTextColor.GOLD));
        }
        if (isMilestoneLevel(config.titleEveryLevels(), level)) {
            player.showTitle(Title.title(
                    Component.text(name + " Lv" + level, NamedTextColor.AQUA),
                    Component.text("レベルアップ！", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600))));
        }
    }

    /** 現レベル内の進捗 [0,1]。span<=0 や最大レベルは 1.0。 */
    private float progressWithin(String skillId, SkillProgress after) {
        double span = spanForLevel(skillId, after.level());
        return progressRatio(after.level(), after.maxAllowedLevel(), after.residualExp(), span);
    }

    /**
     * 純粋関数として切り出したボスバー進捗比率の計算(2026-07-25 テスト容易化のための最小リファクタ、
     * {@link #progressWithin} と完全に同一のロジック): 最大レベル到達なら常に 1.0、span(次レベルまでの
     * 必要EXP幅) が 0 以下(0除算になりうる)なら 1.0、それ以外は {@code residualExp / span} を [0,1] へ
     * クランプする(EXPが次レベル必要量を超えるケースも 1.0 にクランプされる)。
     */
    static float progressRatio(int level, int maxAllowedLevel, double residualExp, double span) {
        if (level >= maxAllowedLevel) {
            return 1.0f;
        }
        if (span <= 0) {
            return 1.0f;
        }
        double p = residualExp / span;
        return (float) Math.max(0.0, Math.min(1.0, p));
    }

    /**
     * 純粋関数として切り出したレベルアップ・タイトル表示の倍数判定(2026-07-25 テスト容易化のための
     * 最小リファクタ、{@link #onLevelUp} 内の従来ロジックと完全に同一): {@code titleEveryLevels} が
     * 0以下なら常に無効、{@code level} が0以下(到達直後の初期状態など)も対象外、それ以外は倍数判定。
     */
    static boolean isMilestoneLevel(int titleEveryLevels, int level) {
        return titleEveryLevels > 0 && level > 0 && level % titleEveryLevels == 0;
    }

    /** レベル {@code level} → {@code level+1} に必要なEXP幅。カーブ未定義/最大レベルは 0。 */
    private double spanForLevel(String skillId, int level) {
        SkillCatalogEntry entry = catalog.get(skillId);
        if (entry == null) {
            return 0.0;
        }
        try {
            XpTransitionService xp = new XpTransitionService(entry.curve());
            double cur = xp.cumulativeExpForLevel(level);
            double next = xp.cumulativeExpForLevel(level + 1);
            return Math.max(0.0, next - cur);
        } catch (RuntimeException ex) {
            return 0.0;
        }
    }

    private static String fmt(double v) {
        return Long.toString(Math.round(v));
    }

    /**
     * 設定のサウンド名を Adventure Key へ。{@code "ENTITY_PLAYER_LEVELUP"}(enum風),
     * {@code "entity.player.levelup"}, {@code "minecraft:entity.player.levelup"} を許容。不正は null。
     */
    private static Key soundKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            if (s.contains(":")) {
                return Key.key(s);
            }
            if (s.indexOf('_') >= 0 && s.equals(s.toUpperCase(Locale.ROOT))) {
                s = s.toLowerCase(Locale.ROOT).replace('_', '.');
            }
            return Key.key(s);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
