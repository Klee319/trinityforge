package com.trinityforge.command;

import com.mojang.brigadier.Command;
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
 * {@code /tf role | /tf role clear} — non-combat role assignment (ROLE_SYSTEM_SPEC §7 MVP)。
 *
 * <p>{@code /tf role}(引数なし)は現在のロール<b>とその効果</b>をチャットへ表示する
 * (以前はロールIDを1行返すだけで、何のバフが乗っているのか確認できなかった)。
 *
 * <p><b>2026-08-05 (W-28): {@code /tf role set} は廃止した。</b>付け替えの動線は
 * {@code /tf status} のロールアイコン → {@link RoleSelectGui} と、転職の証の右クリックだけ。
 * 「同じことをする入口が3つ(コマンド引数版・コマンドGUI版・GUI)」で、どれが正か分からない状態を
 * たたむのが目的なので、<b>ここに set を戻すときは status GUI 側と可否判定を必ず共有すること</b>
 * ({@link RoleChangeService} が単一の出所)。
 */
public final class RoleCommand {

    private final RoleChangeService roleChangeService;
    private final RoleDescriptions descriptions;

    public RoleCommand(RoleChangeService roleChangeService, RoleDescriptions descriptions) {
        this.roleChangeService = Objects.requireNonNull(roleChangeService, "roleChangeService");
        this.descriptions = Objects.requireNonNull(descriptions, "descriptions");
    }

    private RoleBuffsConfig roleBuffs() {
        return roleChangeService.config();
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("role")
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("clear")
                        .executes(ctx -> clear(ctx.getSource())));
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
        player.sendMessage(Component.text("変更: /tf status のロールアイコン / 解除: /tf role clear",
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

    /**
     * {@code /tf role clear} — 解除も交戦中ガードは見ない（外すだけなので戦闘中に塞ぐ理由が無い。
     * 解除→即再選択の迂回路を塞いでいるのは {@code clear} 側の刻印＝クールダウンであってガードではない）。
     */
    private int clear(CommandSourceStack source) {
        if (!ensureCommandEnabled(source)) {
            return 0;
        }
        Player player = (Player) source.getSender();
        roleChangeService.clear(player);
        player.sendMessage(Component.text("ロールをクリアしました。", NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 外すだけの動線({@code clear})のゲート。交戦中ガードは通さない
     * (外すだけなので戦闘中に塞ぐ理由が無い)。
     */
    private boolean ensureCommandEnabled(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤー専用コマンドです。", NamedTextColor.RED));
            return false;
        }
        var deny = roleChangeService.changeDisabledReason(player);
        if (deny.isPresent()) {
            player.sendMessage(Component.text(deny.get(), NamedTextColor.RED));
            return false;
        }
        return true;
    }
}
