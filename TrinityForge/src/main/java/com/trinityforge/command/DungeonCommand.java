package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.config.domains.DungeonGateConfig;
import com.trinityforge.config.domains.DungeonThemeConfig;
import com.trinityforge.mobs.DungeonGate;
import com.trinityforge.mobs.DungeonTeleporter;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code /trinityforge dungeon themes} — lists the dungeon attribute themes defined in
 * {@code dungeon/themes.yml} (concern: support authoring a new dungeon). Pairs with
 * {@code /trinityforge importmobs theme <theme> <folder>} so an admin can discover theme names while
 * setting up a new dungeon.
 *
 * <p>{@code /trinityforge dungeon <id>}(2026-07-27 admin クイック入場)は登録済みゲートID(=行き先
 * ワールド名)へ、戦闘レベル/鍵チェックを一切行わず・鍵も消費せずに入場させる。転送先の決定は
 * {@link DungeonTeleporter}(= {@code DungeonEntryGui} が使うものと同じ)に委ねるため、EliteMobs連携
 * ダンジョンへの委譲/明示座標/区画中心/ワールドスポーンのどれであっても通常入場と同じ経路で届く。
 * 成功時の後処理は無し(no-op) — 鍵チェック自体をスキップしているため消費するものがない。
 *
 * <p>親コマンドの登録側({@code TrinityForge.registerCommands()}）が {@code .requires(isTfAdmin)} を
 * 付けているため、このクラス内では管理者ゲートを重ねて付けない({@code themes} サブコマンドも既に
 * 同じ制約下にある)。
 */
public final class DungeonCommand {

    private final DungeonThemeConfig themeConfig;
    private final DungeonGateConfig gateConfig;
    private final DungeonTeleporter teleporter;

    public DungeonCommand(DungeonThemeConfig themeConfig, DungeonGateConfig gateConfig,
                          DungeonTeleporter teleporter) {
        this.themeConfig = Objects.requireNonNull(themeConfig, "themeConfig");
        this.gateConfig = Objects.requireNonNull(gateConfig, "gateConfig");
        this.teleporter = Objects.requireNonNull(teleporter, "teleporter");
    }

    /** The {@code dungeon} subtree to attach under the {@code trinityforge} root. */
    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("dungeon")
                .then(Commands.literal("themes")
                        .executes(ctx -> listThemes(ctx.getSource().getSender())))
                .then(Commands.argument("id", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            gateConfig.all().keySet().stream()
                                    .filter(id -> id.toLowerCase(Locale.ROOT)
                                            .startsWith(builder.getRemainingLowerCase()))
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> quickEnter(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "id"))));
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

    private int quickEnter(CommandSender sender, String id) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return 0;
        }
        // resolve(): ワールド名だけでなく content-package エイリアスでも引ける(gate() はワールド名のみ)。
        // サジェストに出るのはワールド名だが、EliteMobs のパッケージ名を覚えている管理者がそのまま
        // 打っても通るようにしておく。
        Optional<DungeonGate> gateOpt = gateConfig.resolve(id);
        if (gateOpt.isEmpty()) {
            player.sendMessage(Component.text("登録されていないダンジョンIDです: " + id, NamedTextColor.RED));
            return 0;
        }
        // 鍵チェック/戦闘レベルチェックを一切行わないクイック入場のため、成功後処理は no-op。
        teleporter.proceedToTarget(player, gateOpt.get(), () -> { });
        return Command.SINGLE_SUCCESS;
    }
}
