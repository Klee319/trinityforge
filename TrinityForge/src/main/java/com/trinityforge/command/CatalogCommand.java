package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.items.CatalogBrowseGui;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * {@code /tf catalog} — カタログ閲覧・配布GUIを開く（2026-08-05）。
 *
 * <p>クリエイティブか {@code trinityforge.catalog} 権限を持つ場合だけ開ける
 * （{@link CatalogBrowseGui#mayOpen}）。要望は「クリエイティブのインベントリタブに出したい」で、
 * Java 版ではそれがサーバ側から不可能なため、同じ用途をこの画面で満たしている。
 */
public final class CatalogCommand {

    private final CatalogBrowseGui gui;

    public CatalogCommand(CatalogBrowseGui gui) {
        this.gui = Objects.requireNonNull(gui, "gui");
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("catalog")
                .executes(ctx -> open(ctx.getSource().getSender()));
    }

    private int open(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return 0;
        }
        if (!CatalogBrowseGui.mayOpen(player)) {
            player.sendMessage(Component.text(
                    "カタログはクリエイティブ中、または " + CatalogBrowseGui.PERMISSION
                            + " 権限を持つ場合だけ開けます。", NamedTextColor.RED));
            return 0;
        }
        gui.open(player);
        return Command.SINGLE_SUCCESS;
    }
}
