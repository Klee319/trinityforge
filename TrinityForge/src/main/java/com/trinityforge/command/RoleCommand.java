package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.CombatRoleSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.RoleChangeService;
import com.trinityforge.progression.RoleDescriptions;
import com.trinityforge.progression.RoleSelectGui;
import com.trinityforge.text.MiniText;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

/**
 * {@code /tf role [set [<combat> [support]]] | clear} — non-combat role assignment (ROLE_SYSTEM_SPEC §7 MVP).
 *
 * <p>2026-07-28:
 * <ul>
 *   <li>{@code /tf role}(引数なし)は現在のロール<b>とその効果</b>をチャットへ表示する
 *       (以前はロールIDを1行返すだけで、何のバフが乗っているのか確認できなかった)。</li>
 *   <li>{@code /tf role set}(引数なし)は {@link RoleSelectGui} を開く。引数付きの
 *       {@code set <combat> [support]} は従来どおり。どちらも {@link RoleChangeService} の
 *       同じゲートを通る。</li>
 * </ul>
 */
public final class RoleCommand {

    private final RoleChangeService roleChangeService;
    private final RoleDescriptions descriptions;
    private final RoleSelectGui selectGui;

    public RoleCommand(RoleChangeService roleChangeService, RoleDescriptions descriptions,
                       RoleSelectGui selectGui) {
        this.roleChangeService = Objects.requireNonNull(roleChangeService, "roleChangeService");
        this.descriptions = Objects.requireNonNull(descriptions, "descriptions");
        this.selectGui = Objects.requireNonNull(selectGui, "selectGui");
    }

    private RoleBuffsConfig roleBuffs() {
        return roleChangeService.config();
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("role")
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("clear")
                        .executes(ctx -> clear(ctx.getSource())))
                .then(Commands.literal("set")
                        .executes(ctx -> openGui(ctx.getSource()))
                        .then(Commands.argument("combat", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String rem = builder.getRemainingLowerCase();
                                    roleBuffs().combatRoles().keySet().stream()
                                            .filter(id -> rem.isEmpty() || id.startsWith(rem))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> set(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "combat"), null))
                                .then(Commands.argument("support", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            String rem = builder.getRemainingLowerCase();
                                            roleBuffs().supportRoles().keySet().stream()
                                                    .filter(id -> rem.isEmpty() || id.startsWith(rem))
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> set(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "combat"),
                                                StringArgumentType.getString(ctx, "support"))))));
    }

    /** {@code /tf role} — 現在のロールと、それが実際に乗せているバフを一覧表示する。 */
    private int show(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤー専用コマンドです。", NamedTextColor.RED));
            return 0;
        }
        PlayerData data = PlayerData.of(player);
        CombatRoleSpec combat = data.rolePrimary().map(roleBuffs()::combatRole).orElse(null);
        SupportRoleSpec support = data.roleSupport().map(roleBuffs()::supportRole).orElse(null);

        player.sendMessage(Component.text("=== ロール: " + player.getName() + " ===", NamedTextColor.GOLD));
        sendRole(player, "戦闘職", combat == null ? null : combat.label(),
                combat == null ? List.<String>of() : combat.description(),
                combat == null ? List.of() : descriptions.describeCombat(combat));
        sendRole(player, "補助職", support == null ? null : support.label(),
                support == null ? List.<String>of() : support.description(),
                support == null ? List.of() : descriptions.describeSupport(support));
        // 待ち時間があるなら残りを出す。GUIを開かなくても「今は変えられない」と分かるように。
        long combatWait = roleChangeService.combatCooldownRemainingMillis(player);
        long supportWait = roleChangeService.supportCooldownRemainingMillis(player);
        if (combatWait > 0L || supportWait > 0L) {
            player.sendMessage(Component.text("変更可能まで: 戦闘職 "
                    + (combatWait > 0L ? "あと " + RoleChangeService.formatRemaining(combatWait) : "いつでも")
                    + " / 補助職 "
                    + (supportWait > 0L ? "あと " + RoleChangeService.formatRemaining(supportWait) : "いつでも"),
                    NamedTextColor.YELLOW));
        }
        player.sendMessage(Component.text("変更: /tf role set (GUI) / 解除: /tf role clear",
                NamedTextColor.DARK_GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private static void sendRole(Player player, String heading, String label,
                                 List<String> description, List<Component> effects) {
        // label / description は MiniMessage 可 (アイテムカタログの display-name / lore と同じ記法)。
        player.sendMessage(Component.text(heading + ": ", NamedTextColor.AQUA)
                .append(label == null
                        ? Component.text("(なし)", NamedTextColor.GRAY)
                        : MiniText.render(label, NamedTextColor.WHITE)));
        if (label == null) {
            return;
        }
        for (String line : description) {
            if (line != null && !line.isBlank()) {
                player.sendMessage(Component.text("  ", NamedTextColor.DARK_GRAY)
                        .append(MiniText.render(line, NamedTextColor.DARK_GRAY)));
            }
        }
        if (effects.isEmpty()) {
            player.sendMessage(Component.text("  (効果なし)", NamedTextColor.DARK_GRAY));
            return;
        }
        effects.forEach(player::sendMessage);
    }

    /** {@code /tf role set}(引数なし) — アイテム表示のロール選択GUIを開く。 */
    private int openGui(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤー専用コマンドです。", NamedTextColor.RED));
            return 0;
        }
        // 開く前にもゲートを見る(開いてからクリック時に弾かれるより分かりやすい)。
        var deny = roleChangeService.denyReason(player);
        if (deny.isPresent()) {
            player.sendMessage(Component.text(deny.get(), NamedTextColor.RED));
            return 0;
        }
        selectGui.open(player);
        return Command.SINGLE_SUCCESS;
    }

    private int clear(CommandSourceStack source) {
        if (!ensureAllowed(source)) {
            return 0;
        }
        Player player = (Player) source.getSender();
        roleChangeService.clear(player);
        player.sendMessage(Component.text("ロールをクリアしました。", NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    private int set(CommandSourceStack source, String combatRaw, String supportRaw) {
        if (!ensureAllowed(source)) {
            return 0;
        }
        Player player = (Player) source.getSender();
        // 両方を先に検証してから適用する: 補助職のIDだけ打ち間違えたときに戦闘職だけ書き換わって
        // 終わる(部分適用)のを防ぐ。
        CombatRoleSpec combat = roleBuffs().combatRole(combatRaw);
        if (combat == null) {
            player.sendMessage(Component.text("未知の戦闘職: " + combatRaw, NamedTextColor.RED));
            return 0;
        }
        boolean hasSupport = supportRaw != null && !supportRaw.isBlank();
        SupportRoleSpec support = hasSupport ? roleBuffs().supportRole(supportRaw) : null;
        if (hasSupport && support == null) {
            player.sendMessage(Component.text("未知の補助職: " + supportRaw, NamedTextColor.RED));
            return 0;
        }
        // クールダウンも「両方先に見る」— 補助職だけ待ち時間中なのに戦闘職だけ書き換わって
        // 終わる部分適用を防ぐ(ID誤りの扱いと同じ理由)。同じロールを選び直すだけなら
        // 実際には何も変わらないので待ち時間を見ない。
        PlayerData data = PlayerData.of(player);
        boolean combatChanges = isChange(data.rolePrimary().orElse(null), combatRaw);
        boolean supportChanges = support != null && isChange(data.roleSupport().orElse(null), supportRaw);
        if (combatChanges) {
            var deny = roleChangeService.denyReasonForCombat(player);
            if (deny.isPresent()) {
                player.sendMessage(Component.text(deny.get(), NamedTextColor.RED));
                return 0;
            }
        }
        if (supportChanges) {
            var deny = roleChangeService.denyReasonForSupport(player);
            if (deny.isPresent()) {
                player.sendMessage(Component.text(deny.get(), NamedTextColor.RED));
                return 0;
            }
        }
        roleChangeService.setCombat(player, combatRaw);
        if (support != null) {
            roleChangeService.setSupport(player, supportRaw);
        }
        // チャット1行へ連結するので MiniMessage タグは落とす。
        String supportLabel = support == null ? null : MiniText.plain(support.label());
        String combatLabel = MiniText.plain(combat.label());
        player.sendMessage(Component.text(
                "ロールを設定しました: 戦闘=" + combatLabel
                        + (supportLabel != null ? " / 補助=" + supportLabel : ""),
                NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    /** 現在値と指定値を正規化して比べ、実際に切り替わるかを返す。 */
    private static boolean isChange(String currentId, String rawId) {
        String next = RoleChangeService.normalize(rawId);
        return next != null && !next.equals(currentId);
    }

    private boolean ensureAllowed(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤー専用コマンドです。", NamedTextColor.RED));
            return false;
        }
        var deny = roleChangeService.denyReason(player);
        if (deny.isPresent()) {
            player.sendMessage(Component.text(deny.get(), NamedTextColor.RED));
            return false;
        }
        return true;
    }
}
