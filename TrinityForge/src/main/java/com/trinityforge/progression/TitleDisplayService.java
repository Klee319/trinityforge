package com.trinityforge.progression;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 称号(titles) のネームタグ表示 (2026-07-23-stat-gate-overhaul §6.1)。
 *
 * <p><b>2026-08-02 の書き換え</b>: 旧実装は装備中プレイヤーへ {@code TextDisplay} をパッセンジャーとして
 * マウントし、頭上の別行に称号を浮かせていた。これがバグ報告「称号を付けている人にネームタグが
 * 表示されなかった」の原因になっていた —
 * <ul>
 *   <li>パッセンジャーの頭上オフセットは実サーバで目視確認できないまま当て推量(0.35→0.75)で
 *       調整されており、ネームタグの実際の描画高さと合わせる保証が無かった
 *       (Owen1212055 のInteraction/nametag解説: 騎乗オフセット・Interactionの既定ネームタグ
 *       オフセット・実際のネームタグオフセットの3つを合成しないと正しい位置にならない)。</li>
 *   <li>パッセンジャーが付いたエンティティはプラグインからのテレポート(同一ワールド/ワールド間)を
 *       妨げる副作用がある(同解説に明記)。ダンジョン入口などのテレポートが失敗しうる状態だった。</li>
 * </ul>
 *
 * <p>本実装はスコアボードチームの {@code suffix} でネームタグそのものに称号を織り込む方式へ
 * 置き換える。プレイヤー1人につき専用のチームを1つ割り当て、そのチームの suffix に
 * 装備中の称号テキストを設定する。別エンティティを一切生成しないため:
 * <ul>
 *   <li>ネームタグと物理的に重なりようがない — 「重なる高さ」という当て推量そのものが構造的に
 *       発生しない(調整できるのは {@link com.trinityforge.config.domains.SpecialRewardsConfig#titleSeparator()}
 *       の区切り文字だけ)。</li>
 *   <li>チーム所属はプレイヤー識別子(エントリ名)に紐づくのでエンティティ/パッセンジャーが存在せず、
 *       テレポート/ワールド間移動を一切妨げない。</li>
 *   <li>Display系エンティティの描画が弱い統合版(Bedrock/Geyser)クライアントでも、ネームタグ自体は
 *       確実に描画されるため称号も確実に見える。</li>
 * </ul>
 * 欠点は称号がネームタグと同じ行に出ること(頭上の別行ではなくなる)。
 */
public final class TitleDisplayService implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /**
     * このプラグインが管理するチーム名の接頭辞。歴史的な16文字制限(現行 Paper では撤廃済みだが、
     * 混在運用の安全側マージンとして踏襲する)に収めるため、プレイヤーUUIDの先頭11桁hexと
     * 合わせてちょうど16文字にする。
     */
    private static final String TEAM_PREFIX = "tf_t_";

    private final Plugin plugin;
    /** プレイヤーの現在の装備称号MiniMessage文字列を返す(未装備/未保有なら null)。 */
    private final Function<Player, String> textResolver;
    /** ネームタグとのあいだに挟む区切り文字列(config駆動、{@code /trinityforge reload} で反映)。 */
    private final Supplier<String> separator;

    public TitleDisplayService(Plugin plugin, Function<Player, String> textResolver, Supplier<String> separator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");
        this.separator = Objects.requireNonNull(separator, "separator");
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    /** サーバ停止時に全チームを解除する(次回起動時はスコアボード自体が再構築されるため必須ではないが、
     * ホットリロード可能なプラグイン再読込経路がある場合の孤児防止として行う)。 */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            despawn(player);
        }
    }

    /** 装備状態(称号テキスト)に合わせてネームタグ suffix を張り直す。称号未装備/オフラインなら消すのみ。 */
    public void refresh(Player player) {
        if (!player.isOnline()) {
            return;
        }
        String display = textResolver.apply(player);
        if (display == null || display.isBlank()) {
            despawn(player);
            return;
        }
        Component text;
        try {
            text = MINI_MESSAGE.deserialize(display);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[title-display] invalid MiniMessage for " + player.getName()
                    + ": " + ex.getMessage());
            despawn(player);
            return;
        }
        Team team = teamFor(player, true);
        if (team == null) {
            return;
        }
        team.suffix(Component.text(resolveSeparator()).append(text));
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
    }

    private String resolveSeparator() {
        String value = separator.get();
        return value != null ? value : " ";
    }

    private void despawn(Player player) {
        Team team = teamFor(player, false);
        if (team != null) {
            team.unregister();
        }
    }

    private Team teamFor(Player player, boolean createIfMissing) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            // プラグイン有効化前など、起動シーケンス上は基本発生しない防御的分岐。
            return null;
        }
        Scoreboard scoreboard = manager.getMainScoreboard();
        String name = teamName(player);
        Team team = scoreboard.getTeam(name);
        if (team == null && createIfMissing) {
            team = scoreboard.registerNewTeam(name);
        }
        return team;
    }

    private static String teamName(Player player) {
        String hex = player.getUniqueId().toString().replace("-", "");
        return TEAM_PREFIX + hex.substring(0, 11);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        despawn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }
}
