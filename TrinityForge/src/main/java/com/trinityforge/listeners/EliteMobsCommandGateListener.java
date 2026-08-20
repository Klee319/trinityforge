package com.trinityforge.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Blocks player-facing EliteMobs / AdventurersGuild command aliases while TrinityForge owns
 * progression, shops, and combat. Ops with {@code trinityforge.elitemobs.commands} (or admin) may
 * still use them for dungeon tooling.
 *
 * <p><b>サブコマンド単位の許可リスト方式にした理由(2026-07-27)</b>: 以前は {@code /em}・{@code /elitemobs}
 * をラベル単位で全面ブロックしていたが、これは EliteMobs 自身が内部から {@code performCommand} や
 * チャットの {@code ClickEvent(RUN_COMMAND)} 経由でこのイベントを踏むケースまで巻き込んでいた —
 * ダンジョン入口NPC({@code NPCInteractions} が {@code em dungeontp <package>} を発火)、
 * ボスバーのクリック({@code elitemobs track boss <uuid>}）、ステータス画面ダイアログ
 * ({@code em dungeontpdialog}）など。全面ブロックのままだと EliteMobs のダンジョン導線そのものが
 * 丸ごと死ぬため、第1引数(サブコマンド)単位でプレイヤーに必要なものだけを通す方式に変更した。
 * {@code /ag} 系はTFの経済/進行コマンドと完全に競合するため、従来どおり全面ブロックのままとする。
 */
public final class EliteMobsCommandGateListener implements Listener {

    private static final Component DENIED = Component.text(
            "EliteMobs のプレイヤーコマンドは無効です。進行・ステータスは /skills を使ってください。",
            NamedTextColor.RED);

    private static final String BYPASS = "trinityforge.elitemobs.commands";

    /**
     * {@code /em}・{@code /elitemobs} のうち、権限を持たないプレイヤーにも通すサブコマンド。
     *
     * <p><b>2026-07-30 縮小(ユーザー確定)</b>: 以前は {@code start}/{@code dungeontp}/
     * {@code dungeontpdialog}/{@code spawntp}/{@code arena} も全員に通していたが、これは
     * <b>OP でないプレイヤーが EliteMobs のコマンドをそのまま打てる</b>状態だった。とくに
     * {@code dungeontp} は TF 側の鍵チェック・戦闘レベルチェック({@code DungeonGate} /
     * {@code DungeonEntryGui})を丸ごと迂回してダンジョンへ入れてしまう。
     * {@link org.bukkit.event.player.PlayerCommandPreprocessEvent} からは
     * 「プレイヤーが打った」のか「EliteMobs のチャット {@code ClickEvent} 由来か」を区別できないため、
     * 許可リストを絞る以外に塞ぐ手段が無い。
     *
     * <ul>
     *   <li>{@code quit} — インスタンスからの脱出。塞ぐとダンジョンに閉じ込められうるので必須。</li>
     *   <li>{@code track} — ボスバーのクリック({@code elitemobs track boss <uuid>})。
     *       表示専用で進行を動かさない。</li>
     *   <li>{@code start} — <b>2026-08-17 復活(ユーザー報告)</b>。インスタンス内での開始操作。
     *       {@code dungeontp} と違い<b>入場済みのインスタンスの中でしか意味を持たない</b>ので、
     *       TF 側の鍵チェック・戦闘レベルチェックを迂回しない(入場は既に済んでいる)。
     *       塞いだままだとパーティが揃ってもダンジョンを始められず詰む。
     *       EliteMobs 側の権限 {@code elitemobs.instance.start} は既定 true。</li>
     * </ul>
     *
     * <p>これで塞がれる導線の TF 側の代替: 入場は {@code /tf dungeon}(管理者)と
     * {@code DungeonEntryGui}(鍵アイテム)、インスタンスの開始/終了は {@code /tf start} /
     * {@code /tf stop} / {@code /tf quit}({@code InstanceCommand})。
     */
    private static final List<String> ALLOWED_EM_SUBCOMMANDS = List.of("quit", "track", "start");

    public EliteMobsCommandGateListener() {
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        Objects.requireNonNull(player, "player");
        if (player.hasPermission(BYPASS) || player.hasPermission("trinityforge.admin")) {
            return;
        }
        if (isBlockedCommand(event.getMessage())) {
            event.setCancelled(true);
            player.sendMessage(DENIED);
        }
    }

    /**
     * 生のコマンド文字列({@code "/em start"} など)を解釈してブロック判定する純粋関数。
     * 名前空間付き({@code /minecraft:em ...})、大文字小文字、余分な空白のいずれも吸収する。
     */
    static boolean isBlockedCommand(String raw) {
        if (raw == null || raw.isBlank() || raw.charAt(0) != '/') {
            return false;
        }
        String body = raw.substring(1).trim().toLowerCase(Locale.ROOT);
        int space = body.indexOf(' ');
        String label = space < 0 ? body : body.substring(0, space);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        String remainder = space < 0 ? "" : body.substring(space + 1).trim();
        return isBlocked(label, firstToken(remainder));
    }

    private static String firstToken(String remainder) {
        if (remainder.isEmpty()) {
            return null;
        }
        int nextSpace = remainder.indexOf(' ');
        return nextSpace < 0 ? remainder : remainder.substring(0, nextSpace);
    }

    /**
     * ラベル({@code /command} の1語目)とサブコマンド(2語目、無ければ {@code null})だけから
     * ブロック判定を行う純粋関数(テスト容易性のため副作用なし)。
     *
     * <ul>
     *   <li>{@code ag}/{@code adventurersguild}/{@code adventurers_guild} — 常にブロック。</li>
     *   <li>{@code em}/{@code elitemobs} — {@code firstArg} が {@link #ALLOWED_EM_SUBCOMMANDS} に
     *       含まれるときだけ通す。サブコマンドなし(素の {@code /em})は従来どおりブロック
     *       (TF が進行・ステータス画面を持つため)。</li>
     *   <li>それ以外のラベルはブロック対象外。</li>
     * </ul>
     */
    static boolean isBlocked(String label, String firstArg) {
        return switch (label) {
            case "ag", "adventurersguild", "adventurers_guild" -> true;
            case "em", "elitemobs" -> firstArg == null || !ALLOWED_EM_SUBCOMMANDS.contains(firstArg);
            default -> false;
        };
    }
}
