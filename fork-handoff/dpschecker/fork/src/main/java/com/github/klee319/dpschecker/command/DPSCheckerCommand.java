package com.github.klee319.dpschecker.command;

import com.github.klee319.dpschecker.calculator.DpsSessionManager;
import com.github.klee319.dpschecker.dummy.DummyEntity;
import com.github.klee319.dpschecker.dummy.DummyManager;
import com.github.klee319.dpschecker.gui.MainMenuGUI;
import io.papermc.paper.command.brigadier.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.Command;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("UnstableApiUsage")
public class DPSCheckerCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private final DummyManager dummyManager;
    private final DpsSessionManager sessionManager;

    public DPSCheckerCommand(JavaPlugin plugin, DummyManager dummyManager, DpsSessionManager sessionManager) {
        this.plugin = plugin;
        this.dummyManager = dummyManager;
        this.sessionManager = sessionManager;
    }

    public void register(Commands commands) {
        commands.register(
                Commands.literal("dps")
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                                ctx.getSource().getSender().sendMessage(
                                                        Component.text("プレイヤーのみ実行可能です。", NamedTextColor.RED));
                                                return Command.SINGLE_SUCCESS;
                                            }
                                            String name = StringArgumentType.getString(ctx, "name");
                                            return handleSpawn(player, name);
                                        })
                                )
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                                        ctx.getSource().getSender().sendMessage(
                                                Component.text("プレイヤーのみ実行可能です。", NamedTextColor.RED));
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    return handleSpawn(player, player.getName() + "のダミー");
                                })
                        )
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                                ctx.getSource().getSender().sendMessage(
                                                        Component.text("プレイヤーのみ実行可能です。", NamedTextColor.RED));
                                                return Command.SINGLE_SUCCESS;
                                            }
                                            String name = StringArgumentType.getString(ctx, "name");
                                            return handleRemove(player, name);
                                        })
                                )
                                .executes(ctx -> {
                                    ctx.getSource().getSender().sendMessage(prefix()
                                            .append(Component.text("使い方: /dps remove <名前>", NamedTextColor.RED)));
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("on")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                                ctx.getSource().getSender().sendMessage(
                                                        Component.text("プレイヤーのみ実行可能です。", NamedTextColor.RED));
                                                return Command.SINGLE_SUCCESS;
                                            }
                                            return handleDpsOn(player, StringArgumentType.getString(ctx, "name"));
                                        })
                                )
                                .executes(ctx -> {
                                    ctx.getSource().getSender().sendMessage(prefix()
                                            .append(Component.text("使い方: /dps on <名前>", NamedTextColor.RED)));
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("off")
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                                        ctx.getSource().getSender().sendMessage(
                                                Component.text("プレイヤーのみ実行可能です。", NamedTextColor.RED));
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    return handleDpsOff(player);
                                })
                        )
                        .then(Commands.literal("gui")
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                                        ctx.getSource().getSender().sendMessage(
                                                Component.text("プレイヤーのみ実行可能です。", NamedTextColor.RED));
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    return handleGUI(player);
                                })
                        )
                        .then(Commands.literal("reset")
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                                        ctx.getSource().getSender().sendMessage(
                                                Component.text("プレイヤーのみ実行可能です。", NamedTextColor.RED));
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    return handleReset(player);
                                })
                        )
                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                                        ctx.getSource().getSender().sendMessage(
                                                Component.text("プレイヤーのみ実行可能です。", NamedTextColor.RED));
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    return handleList(player);
                                })
                        )
                        .then(Commands.literal("reload")
                                .requires(src -> src.getSender().hasPermission("dpschecker.admin"))
                                .executes(ctx -> {
                                    plugin.reloadConfig();
                                    ctx.getSource().getSender().sendMessage(prefix()
                                            .append(Component.text("設定をリロードしました。", NamedTextColor.GREEN)));
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .build(),
                "DPSChecker - ダメージテスト用ダミー",
                List.of("dpschecker", "dummy")
        );
    }

    private int handleSpawn(Player player, String name) {
        if (!player.hasPermission("dpschecker.use")) {
            player.sendMessage(prefix().append(Component.text("権限がありません。", NamedTextColor.RED)));
            return Command.SINGLE_SUCCESS;
        }

        int max = dummyManager.getMaxPerPlayer();
        if (dummyManager.getOwnerDummyCount(player.getUniqueId()) >= max) {
            player.sendMessage(prefix().append(MM.deserialize(
                    plugin.getConfig().getString("messages.max-dummies-reached",
                            "<red>ダミーの設置上限に達しています。(<max>体)"),
                    Placeholder.parsed("max", String.valueOf(max)))));
            return Command.SINGLE_SUCCESS;
        }

        String sanitizedName = MM.stripTags(name);
        if (sanitizedName.length() > 64) {
            sanitizedName = sanitizedName.substring(0, 64);
        }

        dummyManager.createDummy(player.getUniqueId(), sanitizedName, player.getLocation());
        player.sendMessage(prefix().append(Component.text(
                "ダミー「" + sanitizedName + "」を設置しました。", NamedTextColor.GREEN)));
        return Command.SINGLE_SUCCESS;
    }

    private int handleRemove(Player player, String name) {
        String sanitized = MM.stripTags(name);
        List<DummyEntity> matched = dummyManager.getDummiesByName(sanitized);
        if (matched.isEmpty()) {
            player.sendMessage(prefix().append(Component.text(
                    "「" + sanitized + "」という名前のカカシは見つかりません。", NamedTextColor.RED)));
            return Command.SINGLE_SUCCESS;
        }

        boolean isAdmin = player.hasPermission("dpschecker.admin");
        long ownedCount = matched.stream()
                .filter(d -> d.getOwnerUuid().equals(player.getUniqueId()) || isAdmin)
                .count();
        if (ownedCount == 0) {
            player.sendMessage(prefix().append(Component.text(
                    "そのカカシは他のプレイヤーが所有しています。", NamedTextColor.RED)));
            return Command.SINGLE_SUCCESS;
        }

        int removed = 0;
        for (DummyEntity dummy : matched) {
            if (dummy.getOwnerUuid().equals(player.getUniqueId()) || isAdmin) {
                dummyManager.removeDummy(dummy.getDummyUuid());
                removed++;
            }
        }
        player.sendMessage(prefix().append(Component.text(
                "「" + sanitized + "」を" + removed + "体撤去しました。", NamedTextColor.GREEN)));
        return Command.SINGLE_SUCCESS;
    }

    private int handleDpsOn(Player player, String name) {
        if (!player.hasPermission("dpschecker.use")) {
            player.sendMessage(prefix().append(Component.text("権限がありません。", NamedTextColor.RED)));
            return Command.SINGLE_SUCCESS;
        }
        if (sessionManager.hasSession(player.getUniqueId())) {
            player.sendMessage(prefix().append(Component.text(
                    "すでに計測中です。/dps off で終了してください。", NamedTextColor.RED)));
            return Command.SINGLE_SUCCESS;
        }
        String sanitized = MM.stripTags(name);
        sessionManager.startSession(player, sanitized);
        return Command.SINGLE_SUCCESS;
    }

    private int handleDpsOff(Player player) {
        boolean stopped = sessionManager.stopSession(player);
        if (!stopped) {
            player.sendMessage(prefix().append(Component.text(
                    "計測中のセッションはありません。", NamedTextColor.YELLOW)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int handleGUI(Player player) {
        Optional<DummyEntity> opt = dummyManager.getNearestDummy(player.getLocation(), 10);
        if (opt.isEmpty()) {
            player.sendMessage(prefix().append(Component.text("近くにダミーがありません。(10ブロック以内)", NamedTextColor.RED)));
            return Command.SINGLE_SUCCESS;
        }

        DummyEntity dummy = opt.get();
        if (!dummy.getOwnerUuid().equals(player.getUniqueId())
                && !player.hasPermission("dpschecker.admin")) {
            player.sendMessage(prefix().append(Component.text("他のプレイヤーのダミーは操作できません。", NamedTextColor.RED)));
            return Command.SINGLE_SUCCESS;
        }

        player.openInventory(new MainMenuGUI(plugin, dummy).getInventory());
        return Command.SINGLE_SUCCESS;
    }

    private int handleReset(Player player) {
        Optional<DummyEntity> opt = dummyManager.getNearestDummy(player.getLocation(), 10);
        if (opt.isEmpty()) {
            player.sendMessage(prefix().append(Component.text("近くにダミーがありません。(10ブロック以内)", NamedTextColor.RED)));
            return Command.SINGLE_SUCCESS;
        }

        DummyEntity dummy = opt.get();
        if (!dummy.getOwnerUuid().equals(player.getUniqueId())
                && !player.hasPermission("dpschecker.admin")) {
            player.sendMessage(prefix().append(Component.text("他のプレイヤーのダミーは操作できません。", NamedTextColor.RED)));
            return Command.SINGLE_SUCCESS;
        }

        dummy.resetRecords();
        player.sendMessage(prefix().append(Component.text("ダメージ記録をリセットしました。", NamedTextColor.GREEN)));
        return Command.SINGLE_SUCCESS;
    }

    private int handleList(Player player) {
        List<DummyEntity> playerDummies = dummyManager.getDummiesByOwner(player.getUniqueId());
        if (playerDummies.isEmpty()) {
            player.sendMessage(prefix().append(Component.text("設置中のダミーはありません。", NamedTextColor.YELLOW)));
            return Command.SINGLE_SUCCESS;
        }

        player.sendMessage(prefix().append(Component.text("あなたのダミー一覧:", NamedTextColor.GOLD)));
        for (int i = 0; i < playerDummies.size(); i++) {
            DummyEntity d = playerDummies.get(i);
            var loc = d.getSpawnLocation();
            player.sendMessage(Component.text(String.format("  %d. %s (%.0f, %.0f, %.0f)",
                    i + 1, d.getName(), loc.getX(), loc.getY(), loc.getZ()), NamedTextColor.WHITE));
        }
        return Command.SINGLE_SUCCESS;
    }

    private Component prefix() {
        return Component.text("[DPSChecker] ", NamedTextColor.GOLD);
    }
}
