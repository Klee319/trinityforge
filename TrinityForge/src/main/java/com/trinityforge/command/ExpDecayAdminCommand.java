package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.progression.DailyExpDiminishing;
import com.trinityforge.progression.DailyExpWindowPersistence;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /tf decay reset <player>} / {@code /tf decay immune <player> <duration>} —
 * 日次EXP逓減の運営操作（2026-08-29）。
 *
 * <p>オンラインのプレイヤーだけを対象にする（{@code /tf progression reset} と同じ）。
 * オフライン分まで触ると、メモリに載っていない蓄積を DB だけ消して
 * 「入ってきた瞬間に古いメモリが書き戻る」穴を作る。
 */
public final class ExpDecayAdminCommand {

    private static final Pattern DURATION =
            Pattern.compile("^(\\d+)(h|m|s)?$", Pattern.CASE_INSENSITIVE);

    private final Plugin plugin;
    private final DailyExpDiminishing diminishing;
    private final DailyExpWindowPersistence persistence;

    public ExpDecayAdminCommand(Plugin plugin,
                                DailyExpDiminishing diminishing,
                                DailyExpWindowPersistence persistence) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.diminishing = Objects.requireNonNull(diminishing, "diminishing");
        this.persistence = persistence;
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("decay")
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(Component.text(
                            "用法: /tf decay reset <player>  /  /tf decay immune <player> <duration>",
                            NamedTextColor.YELLOW));
                    ctx.getSource().getSender().sendMessage(Component.text(
                            "duration は 2h / 30m / 90s / 秒の数字。", NamedTextColor.GRAY));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("reset")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> reset(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("immune")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .executes(ctx -> immune(ctx.getSource().getSender(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "duration"))))));
    }

    private int reset(CommandSender sender, String playerName) {
        Player target = plugin.getServer().getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(Component.text("オンラインのプレイヤーが見つかりません: " + playerName,
                    NamedTextColor.RED));
            return 0;
        }
        diminishing.clearPlayer(target.getUniqueId());
        if (persistence != null) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                    () -> persistence.wipeStored(target.getUniqueId()));
        }
        sender.sendMessage(Component.text(
                "EXP取得量の減衰をリセットしました: " + target.getName(), NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private int immune(CommandSender sender, String playerName, String durationRaw) {
        Player target = plugin.getServer().getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(Component.text("オンラインのプレイヤーが見つかりません: " + playerName,
                    NamedTextColor.RED));
            return 0;
        }
        Optional<Long> millis = parseDurationMillis(durationRaw);
        if (millis.isEmpty()) {
            sender.sendMessage(Component.text(
                    "時間が読めません: " + durationRaw + "  （例: 2h / 30m / 90s / 3600）",
                    NamedTextColor.RED));
            return 0;
        }
        diminishing.grantImmunity(target.getUniqueId(), millis.get());
        if (persistence != null) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                    () -> persistence.persistImmunity(target.getUniqueId()));
        }
        sender.sendMessage(Component.text(
                target.getName() + " のEXP取得量の減衰を "
                        + formatDuration(millis.get()) + " 無効化しました。",
                NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code 2h} / {@code 30m} / {@code 90s} / 単位なしの秒。0 以下は拒否。
     *
     * @return 読めなければ empty
     */
    static Optional<Long> parseDurationMillis(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = DURATION.matcher(raw.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        long value;
        try {
            value = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        if (value <= 0L) {
            return Optional.empty();
        }
        String unit = matcher.group(2);
        long millis;
        if (unit == null) {
            millis = value * 1000L;
        } else {
            millis = switch (unit.toLowerCase(Locale.ROOT)) {
                case "h" -> value * 60L * 60L * 1000L;
                case "m" -> value * 60L * 1000L;
                case "s" -> value * 1000L;
                default -> -1L;
            };
        }
        if (millis <= 0L) {
            return Optional.empty();
        }
        return Optional.of(millis);
    }

    static String formatDuration(long millis) {
        if (millis >= 60L * 60L * 1000L && millis % (60L * 60L * 1000L) == 0L) {
            return (millis / (60L * 60L * 1000L)) + "時間";
        }
        if (millis >= 60L * 1000L && millis % (60L * 1000L) == 0L) {
            return (millis / (60L * 1000L)) + "分";
        }
        return (millis / 1000L) + "秒";
    }
}
