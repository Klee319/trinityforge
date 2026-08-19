package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.trinityforge.mail.MailComposeGui;
import com.trinityforge.mail.MailInboxGui;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * {@code /tf mail} — 受信箱、{@code /tf mail compose [名前|all]} — 送信GUI（2026-08-19 / W-155）。
 *
 * <p>受信箱は全プレイヤーが開ける。送信は運営だけ（{@code compose} ノードにだけ権限述語を掛ける）。
 * <b>受信側の入口をコマンド1本に寄せてある</b>のは、2026-08-05 (W-28) に統合メニューを廃止した
 * ときの判断（入口が二重化するとどちらが正か分からなくなる）と揃えるため。
 * GUI からの導線は {@code /tf settings} のボタンが担う。
 */
public final class MailCommand {

    private final MailInboxGui inboxGui;
    private final MailComposeGui composeGui;
    private final Predicate<CommandSourceStack> adminCheck;

    public MailCommand(MailInboxGui inboxGui, MailComposeGui composeGui,
                       Predicate<CommandSourceStack> adminCheck) {
        this.inboxGui = Objects.requireNonNull(inboxGui, "inboxGui");
        this.composeGui = Objects.requireNonNull(composeGui, "composeGui");
        this.adminCheck = Objects.requireNonNull(adminCheck, "adminCheck");
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("mail")
                .executes(ctx -> openInbox(ctx.getSource().getSender()))
                .then(Commands.literal("compose")
                        .requires(adminCheck)
                        .executes(ctx -> openCompose(ctx.getSource().getSender(), null))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestTargets(builder))
                                .executes(ctx -> openCompose(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "target")))));
    }

    private int openInbox(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return 0;
        }
        inboxGui.open(player);
        return Command.SINGLE_SUCCESS;
    }

    private int openCompose(CommandSender sender, String target) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("メール作成GUIはプレイヤーのみ開けます"
                    + "（添付アイテムを置く必要があるため）。", NamedTextColor.RED));
            return 0;
        }
        composeGui.open(player, target);
        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggestTargets(SuggestionsBuilder builder) {
        String prefix = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
        if ("all".startsWith(prefix)) {
            builder.suggest("all");
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                builder.suggest(online.getName());
            }
        }
        return builder.buildFuture();
    }
}
