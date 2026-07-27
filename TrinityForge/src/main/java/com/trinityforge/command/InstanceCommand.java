package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.mobs.EliteMobsInstanceBridge;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /tf start} / {@code /tf stop}(エイリアス {@code /tf quit}) — EliteMobs のインスタンス
 * (アリーナ/ダンジョン)開始・離脱をTF語彙のコマンドとして提供する(2026-07-27)。
 *
 * <p>{@code /em start}・{@code /em quit} は本来プレイヤーが使えるはずのコマンドだが、
 * {@code EliteMobsCommandGateListener} がTF進行導入前提で {@code /em} 系を全面ブロックしていたため、
 * サブコマンド許可リストで通す(ゲート側の変更)と併せて、TF側の正式な入口としてこのコマンドを追加する。
 * 誰でも使える({@code /tf} ルートの {@code trinityforge.use} でアクセス可)プレイヤー専用コマンドで、
 * {@code requires} は付けない。実体は {@link EliteMobsInstanceBridge} へのリフレクション委譲。
 */
public final class InstanceCommand {

    public InstanceCommand() {
    }

    /** {@code start} リテラル。 */
    public LiteralArgumentBuilder<CommandSourceStack> startNode() {
        return Commands.literal("start")
                .executes(ctx -> start(ctx.getSource().getSender()));
    }

    /** {@code stop} リテラル。 */
    public LiteralArgumentBuilder<CommandSourceStack> stopNode() {
        return Commands.literal("stop")
                .executes(ctx -> stop(ctx.getSource().getSender()));
    }

    /** EliteMobsの語彙({@code /em quit})になじみのあるプレイヤー向けエイリアス(stopと同じ動作)。 */
    public LiteralArgumentBuilder<CommandSourceStack> quitNode() {
        return Commands.literal("quit")
                .executes(ctx -> stop(ctx.getSource().getSender()));
    }

    private int start(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return 0;
        }
        if (!EliteMobsInstanceBridge.isAvailable()) {
            player.sendMessage(Component.text("EliteMobs が利用できないため実行できません", NamedTextColor.RED));
            return 0;
        }
        if (!EliteMobsInstanceBridge.startMatch(player)) {
            player.sendMessage(Component.text(
                    "インスタンス(ダンジョン/アリーナ)の待機中ではありません", NamedTextColor.RED));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int stop(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return 0;
        }
        if (!EliteMobsInstanceBridge.isAvailable()) {
            player.sendMessage(Component.text("EliteMobs が利用できないため実行できません", NamedTextColor.RED));
            return 0;
        }
        if (!EliteMobsInstanceBridge.leaveMatch(player)) {
            player.sendMessage(Component.text("参加中のインスタンスがありません", NamedTextColor.RED));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }
}
