package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.integration.ars.ArsRecipeBrowserBridge;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /tf recipes} — ArsPaper のレシピ一覧GUI({@code RecipeBrowserGui})をTF語彙の
 * コマンドとして提供する(2026-07-28)。
 *
 * <p>従来 {@code /ars recipes} が担っていた入口を撤去し、TF側をレシピ一覧GUIの唯一の入口とする
 * (ユーザー確定仕様)。{@link InstanceCommand} と同じく誰でも使える({@code /tf} ルートの
 * {@code trinityforge.use} でアクセス可)プレイヤー専用コマンドで、{@code requires} は付けない。
 * 実体は {@link ArsRecipeBrowserBridge} へのリフレクション委譲。
 */
public final class RecipesCommand {

    public RecipesCommand() {
    }

    /** {@code recipes} リテラル。 */
    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("recipes")
                .executes(ctx -> open(ctx.getSource().getSender()));
    }

    /** package-private for direct unit testing (mirrors {@code BindCommand#resolveTarget}'s idiom). */
    int open(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return 0;
        }
        if (!ArsRecipeBrowserBridge.isAvailable()) {
            player.sendMessage(Component.text("ArsPaper が利用できないため実行できません", NamedTextColor.RED));
            return 0;
        }
        if (!ArsRecipeBrowserBridge.open(player)) {
            player.sendMessage(Component.text("レシピ一覧を開けませんでした", NamedTextColor.RED));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }
}
