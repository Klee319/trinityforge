package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.listeners.PickupQualityListener;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * {@code /trinityforge stamp [player]} — force-runs the pickup quality/owner sweep on a player's
 * inventory (console/RCON friendly). Used when items arrive via {@code /give} or {@code /item replace}
 * without firing inventory events.
 */
public final class StampCommand {

    private final PickupQualityListener pickupQuality;

    public StampCommand(PickupQualityListener pickupQuality) {
        this.pickupQuality = Objects.requireNonNull(pickupQuality, "pickupQuality");
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("stamp")
                .executes(ctx -> stamp(ctx.getSource(), null))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> stamp(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"))));
    }

    private int stamp(CommandSourceStack source, String playerName) {
        CommandSender sender = source.getSender();
        Player target;
        if (playerName == null || playerName.isBlank()) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(Component.text(
                        "Console: /trinityforge stamp <player>", NamedTextColor.RED));
                return 0;
            }
            target = self;
        } else {
            target = Bukkit.getPlayerExact(playerName);
            if (target == null) {
                sender.sendMessage(Component.text("Player not online: " + playerName, NamedTextColor.RED));
                return 0;
            }
        }
        pickupQuality.sweepInventory(target);
        sender.sendMessage(Component.text(
                "Stamped inventory sweep for " + target.getName(), NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }
}
