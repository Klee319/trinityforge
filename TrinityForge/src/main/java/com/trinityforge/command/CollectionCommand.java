package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.CollectionGui;
import com.trinityforge.progression.CollectionService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;

/**
 * {@code /tf collection} — コレクション図鑑 (M7) のページ付きGUIを開く
 * (2026-07-23-stat-gate-overhaul §6.3: カテゴリタブ + ページング + ホバー詳細)。
 * 表示前に未解放の到達済みティアを追い付き解放する(config編集で後からティアが増えた場合)。
 *
 * <p>{@code /tf collection unmark [player]} — <b>クリエイティブ由来マーカーを剥がす管理コマンド</b>
 * (2026-08-01)。詳細は {@link #unmarkNode()}。
 */
public final class CollectionCommand {

    /**
     * 管理サブコマンド({@code unmark})の権限ノード。
     *
     * <p>{@code /tf collection} 自体はプレイヤー向けなので登録側で {@code .requires} が付いておらず、
     * この管理サブコマンドは<b>自分で</b>権限を要求しなければならない。判定式は
     * {@code TrinityForge#isTfAdmin} と同じ(OP または本権限)。
     * 食い違うと管理コマンドが誰でも撃てる形で漏れるので、
     * {@code CollectionCommandUnmarkTest#adminPermissionNodeMatchesTheWiring} が
     * {@code TrinityForge.java} の実リテラルと機械的に突き合わせている。
     */
    public static final String ADMIN_PERMISSION = "trinityforge.admin";

    private final CollectionConfig config;
    private final CollectionService service;
    private final CollectionGui gui;

    public CollectionCommand(CollectionConfig config, CollectionService service, CollectionGui gui) {
        this.config = Objects.requireNonNull(config, "config");
        this.service = Objects.requireNonNull(service, "service");
        this.gui = Objects.requireNonNull(gui, "gui");
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("collection")
                .executes(ctx -> show(ctx.getSource().getSender()))
                .then(unmarkNode());
    }

    /**
     * {@code /tf collection unmark [player]} — 対象プレイヤーのインベントリ(装備・オフハンド・
     * エンダーチェストを含む)から<b>クリエイティブ由来マーカーを剥がす</b>。
     *
     * <p><b>なぜ剥がす経路が必要か</b>: 印は best-effort で、正当に入手した品にも付きうる
     * ({@code CollectionListener} のクラス javadoc「印が誤って付く側」)。印が付いたスタックは
     * 図鑑判定から丸ごと外れるので、誤って付くと<b>そのスタックは以後どのサバイバル走査でも
     * 永久に図鑑に載らない</b>。エラーも通知も出ないため、剥がす手段が無いと
     * 「このアイテムは一生図鑑に載らない」状態を運用で回復できない。
     *
     * <p>エンダーチェストも対象にするのは、遺物系(エリトラ/トーテム/レコード/バナー模様)が
     * そこにしまわれていることが多く、図鑑の走査対象になるタイミングでインベントリへ
     * 出し直される品だから。
     */
    private LiteralArgumentBuilder<CommandSourceStack> unmarkNode() {
        return Commands.literal("unmark")
                .requires(CollectionCommand::isAdmin)
                .executes(ctx -> unmark(ctx.getSource().getSender(), null))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> unmark(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "player"))));
    }

    /** {@code TrinityForge#isTfAdmin} と同じ判定(OP または {@link #ADMIN_PERMISSION})。 */
    private static boolean isAdmin(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        return sender.isOp() || sender.hasPermission(ADMIN_PERMISSION);
    }

    private int show(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return 0;
        }
        if (!config.enabled()) {
            player.sendMessage(Component.text("コレクション図鑑は無効化されています。", NamedTextColor.GRAY));
            return Command.SINGLE_SUCCESS;
        }
        // config編集でティアが増えていた場合の追い付き解放(冪等)。
        service.grantPendingTiers(player);
        gui.open(player);
        return Command.SINGLE_SUCCESS;
    }

    private int unmark(CommandSender sender, String playerName) {
        Player target;
        if (playerName == null || playerName.isBlank()) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(Component.text(
                        "コンソールからは対象を指定してください: /tf collection unmark <player>",
                        NamedTextColor.RED));
                return 0;
            }
            target = self;
        } else {
            target = Bukkit.getPlayerExact(playerName);
            if (target == null) {
                sender.sendMessage(Component.text(
                        "オンラインではありません: " + playerName, NamedTextColor.RED));
                return 0;
            }
        }
        int cleared = clearCreativeOrigin(target.getInventory())
                + clearCreativeOrigin(target.getEnderChest());
        sender.sendMessage(Component.text(
                target.getName() + " のクリエイティブ由来マーカーを " + cleared + " 件剥がしました。",
                cleared > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * インベントリ全スロットから印を剥がす。書き戻しは<b>実際に剥がしたスロットだけ</b>に限る
     * (無条件に {@code setItem} すると全スロットがクライアントへ再送信されてちらつく。
     * {@code PickupQualityListener} が同じ理由で同じ形にしてある)。
     *
     * @return 剥がしたスタック数
     */
    static int clearCreativeOrigin(Inventory inventory) {
        int cleared = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = stack.getItemMeta();
            if (meta == null || !ItemData.of(meta).clearCreativeOrigin()) {
                continue;
            }
            stack.setItemMeta(meta);
            inventory.setItem(slot, stack);
            cleared++;
        }
        return cleared;
    }
}
