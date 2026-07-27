package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemFactory;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /trinityforge bind setowner <player>} / {@code clear} — set or clear ownership on the
 * held item. Used primarily for {@link BindType#OWNER_BOUND}; also works for SOULBOUND.
 */
public final class BindCommand {

    private final ItemFactory itemFactory;

    public BindCommand(ItemFactory itemFactory) {
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("bind")
                .then(Commands.literal("setowner")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> setOwner(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("clear")
                        .executes(ctx -> clearOwner(ctx.getSource())));
    }

    private int setOwner(CommandSourceStack source, String playerName) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(Component.text("Only a player can bind the held item.", NamedTextColor.RED));
            return 0;
        }

        Optional<OfflinePlayer> resolved = resolveTarget(playerName);
        if (resolved.isEmpty()) {
            sender.sendMessage(Component.text("Unknown player: " + playerName, NamedTextColor.RED));
            return 0;
        }
        OfflinePlayer target = resolved.get();
        UUID ownerId = target.getUniqueId();

        ItemStack stack = admin.getInventory().getItemInMainHand();
        if (!applyOwner(admin, stack, Optional.of(ownerId))) {
            return 0;
        }
        String display = target.getName() != null ? target.getName() : playerName;
        sender.sendMessage(Component.text("Owner set to " + display + ".", NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Resolves a bind target for {@code /trinityforge bind setowner <player>}: (a) an exact online
     * match, then (b) a player who has genuinely joined this server before (a cached, real UUID).
     * Deliberately never falls back to {@link Bukkit#getOfflinePlayer(String)}: that method
     * fabricates an offline-mode UUID for ANY name, including one that was mistyped and never
     * belonged to a real player, which would otherwise soulbind the item to a ghost UUID with no
     * way to recover it. Package-visible for direct unit testing.
     */
    static Optional<OfflinePlayer> resolveTarget(String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            return Optional.of(online);
        }
        return Optional.ofNullable(Bukkit.getOfflinePlayerIfCached(playerName));
    }

    private int clearOwner(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(Component.text("Only a player can clear the held item owner.", NamedTextColor.RED));
            return 0;
        }
        ItemStack stack = admin.getInventory().getItemInMainHand();
        if (!applyOwner(admin, stack, Optional.empty())) {
            return 0;
        }
        sender.sendMessage(Component.text("Owner cleared.", NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private boolean applyOwner(Player admin, ItemStack stack, Optional<UUID> newOwner) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            admin.sendMessage(Component.text("Hold a TrinityForge item in your main hand.", NamedTextColor.RED));
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        if (newOwner.isPresent()) {
            Optional<BindType> bind = data.bindType();
            if (bind.isEmpty() || !bind.get().enforcesOwnership()) {
                data.setBindType(BindType.OWNER_BOUND);
            }
            data.setOwner(newOwner.get());
        } else {
            data.clearOwner();
        }
        stack.setItemMeta(meta);
        Optional<Long> seed = ItemData.of(stack.getItemMeta()).rollSeed();
        if (seed.isPresent()) {
            itemFactory.stamp(stack, seed.get(), ItemData.of(stack.getItemMeta()).quality());
        }
        admin.getInventory().setItemInMainHand(stack);
        return true;
    }
}
