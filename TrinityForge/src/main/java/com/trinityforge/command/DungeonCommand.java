package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.config.domains.DungeonGateConfig;
import com.trinityforge.config.domains.DungeonThemeConfig;
import com.trinityforge.mobs.DungeonGate;
import com.trinityforge.mobs.DungeonTeleporter;
import com.trinityforge.mobs.EliteMobsDungeonBridge;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * <p>2026-07-28: {@code gates.yml} に未登録でも<b>インストール済みの EliteMobs コンテンツパッケージ</b>
 * ならサジェストに出し、入場もできるようにした。{@code gates.yml} は「鍵/戦闘レベルを課したい
 * ダンジョン」を書く場所であって入場可能なダンジョンの一覧ではないため、インポート済みダンジョンが
 * 候補にすら出ないのは不具合だった。
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
                            String prefix = builder.getRemainingLowerCase();
                            for (String id : suggestionIds()) {
                                if (id.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                                    builder.suggest(id);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> quickEnter(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "id"))));
    }

    /**
     * サジェストに出す候補(2026-07-28 修正)。以下3種の和集合を挿入順で返す:
     * <ol>
     *   <li>{@code gates.yml} のゲートID(=行き先ワールド名)</li>
     *   <li>そのゲートの {@code content-package}/{@code aliases}
     *       — {@link #quickEnter} は前から {@code resolve()} でこれも受け付けていたのに、
     *       サジェストには出していなかった</li>
     *   <li><b>インストール済みの EliteMobs コンテンツパッケージ</b>
     *       — {@code /tf importmobs} でインポート済みでも {@code gates.yml} に未登録なら
     *       候補にすら出てこなかった(本不具合の主因)。gates.yml は「鍵/戦闘レベルを課したい
     *       ダンジョン」を書く場所であって、管理者クイック入場の対象一覧ではない</li>
     * </ol>
     * 重複は {@link LinkedHashSet} で除く(ワールド名とパッケージ名が同綴りのケースがある)。
     */
    private Set<String> suggestionIds() {
        Set<String> ids = new LinkedHashSet<>(gateConfig.all().keySet());
        for (DungeonGate gate : gateConfig.all().values()) {
            ids.addAll(gate.aliases());
        }
        ids.addAll(EliteMobsDungeonBridge.installedContentPackageIds());
        return ids;
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
        Optional<DungeonGate> gateOpt = gateConfig.resolve(id);
        if (gateOpt.isPresent()) {
            // 鍵チェック/戦闘レベルチェックを一切行わないクイック入場のため、成功後処理は no-op。
            teleporter.proceedToTarget(player, gateOpt.get(), () -> { });
            return Command.SINGLE_SUCCESS;
        }
        // gates.yml に無い = 「インポート済みだが鍵/レベルゲートを設けていない EliteMobs ダンジョン」。
        // 2026-07-28: 従来はここで一律エラーにしていたため、インポート済みダンジョンへ管理者が
        // クイック入場できなかった。gates.yml はゲート(鍵・戦闘レベル)を課す対象を書く場所であって、
        // 入場可能なダンジョンの一覧ではないので、EM 側の索引へ直接フォールバックする。
        return quickEnterEliteMobs(player, id);
    }

    /**
     * {@code gates.yml} に登録の無い EliteMobs コンテンツパッケージへの直接入場。
     *
     * <p>{@link EliteMobsDungeonBridge#canEnter} を必ず先に通す — {@code DungeonCommands.teleport} は
     * {@code void} で、未インストール/他インスタンス参加中/テレポート先未設定のいずれでも例外を投げず
     * 静かに戻るため、通してしまうと「コマンドは成功したのに何も起きない」になる。
     * ここは鍵を消費しないクイック入場なので鍵消失事故は起きないが、無言の失敗は同じく困る。
     */
    private int quickEnterEliteMobs(Player player, String id) {
        if (!EliteMobsDungeonBridge.isAvailable()) {
            player.sendMessage(Component.text("登録されていないダンジョンIDです: " + id, NamedTextColor.RED));
            return 0;
        }
        if (!EliteMobsDungeonBridge.canEnter(player, id)) {
            player.sendMessage(Component.text(
                    "入場できません: " + id + " (未登録 / 未インストール / 既に別インスタンスに参加中 / "
                            + "テレポート先が未設定 のいずれか)", NamedTextColor.RED));
            return 0;
        }
        if (!EliteMobsDungeonBridge.teleport(player, id)) {
            player.sendMessage(Component.text(
                    "EliteMobs へのテレポート要求に失敗しました: " + id, NamedTextColor.RED));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }
}
