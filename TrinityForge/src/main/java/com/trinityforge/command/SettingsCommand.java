package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.progression.SettingsGui;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * {@code /tf settings} — 称号/パーティクル選択 + 他人の演出非表示トグルGUIを開く
 * (2026-07-23-stat-gate-overhaul §6.1)。
 */
public final class SettingsCommand {

    private final SettingsGui gui;

    public SettingsCommand(SettingsGui gui) {
        this.gui = Objects.requireNonNull(gui, "gui");
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("settings")
                .executes(ctx -> open(ctx.getSource().getSender()));
    }

    private int open(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return 0;
        }
        gui.open(player);
        return Command.SINGLE_SUCCESS;
    }
}
