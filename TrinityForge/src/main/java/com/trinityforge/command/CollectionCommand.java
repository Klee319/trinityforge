package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.progression.CollectionGui;
import com.trinityforge.progression.CollectionService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * {@code /tf collection} — コレクション図鑑 (M7) のページ付きGUIを開く
 * (2026-07-23-stat-gate-overhaul §6.3: カテゴリタブ + ページング + ホバー詳細)。
 * 表示前に未解放の到達済みティアを追い付き解放する(config編集で後からティアが増えた場合)。
 */
public final class CollectionCommand {

    private final CollectionConfig config;
    private final CollectionService service;
    private final CollectionGui gui;

    public CollectionCommand(CollectionConfig config, CollectionService service, CollectionGui gui) {
        this.config = Objects.requireNonNull(config, "config");
        this.service = Objects.requireNonNull(service, "service");
        this.gui = Objects.requireNonNull(gui, "gui");
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("collection")
                .executes(ctx -> show(ctx.getSource().getSender()));
    }

    private int show(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return 0;
        }
        if (!config.enabled()) {
            player.sendMessage(Component.text("コレクション図鑑は無効化されています。", NamedTextColor.GRAY));
            return Command.SINGLE_SUCCESS;
        }
        // config編集でティアが増えていた場合の追い付き解放(冪等)。
        service.grantPendingTiers(player);
        gui.open(player);
        return Command.SINGLE_SUCCESS;
    }
}
