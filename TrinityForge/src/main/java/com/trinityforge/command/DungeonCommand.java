package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.config.domains.DungeonThemeConfig;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.Objects;

/**
 * {@code /trinityforge dungeon themes} — lists the dungeon attribute themes defined in
 * {@code dungeon/themes.yml} (concern: support authoring a new dungeon). Pairs with
 * {@code /trinityforge importmobs theme <theme> <folder>} so an admin can discover theme names while
 * setting up a new dungeon. The parent command gates on {@code trinityforge.admin}.
 */
public final class DungeonCommand {

    private final DungeonThemeConfig themeConfig;

    public DungeonCommand(DungeonThemeConfig themeConfig) {
        this.themeConfig = Objects.requireNonNull(themeConfig, "themeConfig");
    }

    /** The {@code dungeon} subtree to attach under the {@code trinityforge} root. */
    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("dungeon")
                .then(Commands.literal("themes")
                        .executes(ctx -> listThemes(ctx.getSource().getSender())));
    }

    private int listThemes(CommandSender sender) {
        if (themeConfig.names().isEmpty()) {
            sender.sendMessage(Component.text(
                    "No dungeon themes defined (dungeon/themes.yml).", NamedTextColor.YELLOW));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(Component.text("Dungeon themes: " + String.join(", ", themeConfig.names())
                + ". Apply with /trinityforge importmobs theme <theme> <folder>.", NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }
}
