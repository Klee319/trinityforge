package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.listeners.RoleBuffListener;
import com.trinityforge.pdc.PlayerData;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;

/**
 * {@code /tf role [set <combat> [support]] | clear} — non-combat role assignment (ROLE_SYSTEM_SPEC §7 MVP).
 */
public final class RoleCommand {

    private final RoleBuffsConfig roleBuffs;
    private final RoleBuffListener roleBuffListener;

    public RoleCommand(RoleBuffsConfig roleBuffs, RoleBuffListener roleBuffListener) {
        this.roleBuffs = Objects.requireNonNull(roleBuffs, "roleBuffs");
        this.roleBuffListener = Objects.requireNonNull(roleBuffListener, "roleBuffListener");
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("role")
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("clear")
                        .executes(ctx -> clear(ctx.getSource())))
                .then(Commands.literal("set")
                        .then(Commands.argument("combat", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String rem = builder.getRemainingLowerCase();
                                    roleBuffs.combatRoles().keySet().stream()
                                            .filter(id -> rem.isEmpty() || id.startsWith(rem))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> set(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "combat"), null))
                                .then(Commands.argument("support", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            String rem = builder.getRemainingLowerCase();
                                            roleBuffs.supportRoles().keySet().stream()
                                                    .filter(id -> rem.isEmpty() || id.startsWith(rem))
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> set(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "combat"),
                                                StringArgumentType.getString(ctx, "support"))))));
    }

    private int show(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤー専用コマンドです。", NamedTextColor.RED));
            return 0;
        }
        PlayerData data = PlayerData.of(player);
        String combat = data.rolePrimary().orElse("(なし)");
        String support = data.roleSupport().orElse("(なし)");
        player.sendMessage(Component.text("戦闘職: " + combat + " / 補助職: " + support, NamedTextColor.GOLD));
        return Command.SINGLE_SUCCESS;
    }

    private int clear(CommandSourceStack source) {
        if (!ensureAllowed(source)) {
            return 0;
        }
        Player player = (Player) source.getSender();
        PlayerData data = PlayerData.of(player);
        data.clearRoles();
        roleBuffListener.refreshSupportBuff(player);
        player.sendMessage(Component.text("ロールをクリアしました。", NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    private int set(CommandSourceStack source, String combatRaw, String supportRaw) {
        if (!ensureAllowed(source)) {
            return 0;
        }
        Player player = (Player) source.getSender();
        String combat = normalizeRole(combatRaw, roleBuffs.combatRoles().containsKey(normalize(combatRaw)));
        if (combat == null) {
            player.sendMessage(Component.text("未知の戦闘職: " + combatRaw, NamedTextColor.RED));
            return 0;
        }
        String support = null;
        if (supportRaw != null && !supportRaw.isBlank()) {
            support = normalizeRole(supportRaw, roleBuffs.supportRoles().containsKey(normalize(supportRaw)));
            if (support == null) {
                player.sendMessage(Component.text("未知の補助職: " + supportRaw, NamedTextColor.RED));
                return 0;
            }
        }
        PlayerData data = PlayerData.of(player);
        data.setRolePrimary(combat);
        if (support != null) {
            data.setRoleSupport(support);
        }
        roleBuffListener.refreshSupportBuff(player);
        player.sendMessage(Component.text(
                "ロールを設定しました: 戦闘=" + combat + (support != null ? " / 補助=" + support : ""),
                NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private boolean ensureAllowed(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤー専用コマンドです。", NamedTextColor.RED));
            return false;
        }
        if (!roleBuffs.allowRoleCommand()) {
            player.sendMessage(Component.text("コマンドによるロール変更は無効です。", NamedTextColor.RED));
            return false;
        }
        if (player.getNearbyEntities(16, 16, 16).stream().anyMatch(Monster.class::isInstance)) {
            player.sendMessage(Component.text("近くに敵モブがいるためロール変更できません。", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private static String normalizeRole(String raw, boolean valid) {
        if (raw == null || raw.isBlank() || !valid) {
            return null;
        }
        return normalize(raw);
    }

    private static String normalize(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
