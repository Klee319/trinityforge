package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.DerivedItemStats;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /trinityforge inspect [player]} — dumps mainhand TF identity (rollSeed/quality/bind/owner/
 * catalog) to the command source. Console/RCON friendly so smoke tests can verify stamps without a
 * player chat UI.
 */
public final class InspectCommand {

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("inspect")
                .executes(ctx -> inspect(ctx.getSource(), null))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> inspect(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"))));
    }

    private int inspect(CommandSourceStack source, String playerName) {
        CommandSender sender = source.getSender();
        Player target;
        if (playerName == null || playerName.isBlank()) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(Component.text(
                        "Console must specify: /trinityforge inspect <player>", NamedTextColor.RED));
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
        ItemStack main = target.getInventory().getItemInMainHand();
        for (String line : describe(main, target.getName())) {
            sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    static List<String> describe(ItemStack item, String playerName) {
        List<String> lines = new ArrayList<>();
        lines.add("=== TF inspect: " + playerName + " mainhand ===");
        if (item == null || item.getType().isAir()) {
            lines.add("material: AIR");
            return lines;
        }
        lines.add("material: " + item.getType().name());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            lines.add("meta: null");
            return lines;
        }
        Integer cmd = DerivedItemStats.customModelDataOf(meta);
        lines.add("cmd: " + (cmd == null ? "(none)" : cmd));
        ItemData data = ItemData.of(meta);
        lines.add("rollSeed: " + (data.hasRollSeed() ? data.rollSeed().orElseThrow() : "(none)"));
        lines.add("quality: " + data.quality());
        lines.add("catalogId: " + data.catalogId().map(Object::toString).orElse("(none)"));
        lines.add("bindType: " + data.bindType().map(Enum::name).orElse("(none)"));
        lines.add("owner: " + data.owner().map(Object::toString).orElse("(none)"));
        Map<String, AttributeDump> attrs = new TreeMap<>();
        var modifiers = meta.getAttributeModifiers();
        if (modifiers != null) {
            modifiers.forEach((attr, mod) -> attrs.put(attr.getKey().toString() + "|" + mod.getKey(),
                    new AttributeDump(attr.getKey().toString(), mod.getKey().toString(), mod.getAmount())));
        }
        if (attrs.isEmpty()) {
            lines.add("attributes: (none explicit)");
        } else {
            lines.add("attributes:");
            for (AttributeDump dump : attrs.values()) {
                lines.add("  " + dump.attribute() + " " + dump.key() + " = " + dump.amount());
            }
        }
        return lines;
    }

    private record AttributeDump(String attribute, String key, double amount) {
    }
}
