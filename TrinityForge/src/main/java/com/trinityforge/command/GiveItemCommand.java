package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ArsItemGiveBridge;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.MaterialTier;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * {@code /trinityforge give <item> [quality] [amount] [player]} — catalog items and ArsPaper player items
 * (spellbooks/materials/…). Apparatus (pedestal/jars/sourcelinks) stay on {@code /ars give}.
 */
public final class GiveItemCommand {

    /**
     * {@code amount} の上限。プレイヤーインベントリ36枠 × 最大スタック64 = 2304 個で、
     * 「1回で持ちきれる最大」を超えない。これ以上はどう転んでも足元へ落ちるだけなので受け付けない
     * (装備のように1個ずつ組み立てる品では、大きな値がそのまま生成回数になるため上限は必須)。
     */
    private static final int MAX_AMOUNT = 2304;

    private final Plugin plugin;
    private final ItemFactory factory;
    private final ItemCatalogConfig catalog;
    private final QualityConfig quality;
    private final CrossPluginItemResolver resolver;

    public GiveItemCommand(Plugin plugin, ItemFactory factory, ItemCatalogConfig catalog,
                           QualityConfig quality) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.resolver = new CrossPluginItemResolver(catalog, factory);
    }

    /**
     * {@code /tf give <item> [quality] [amount] [player]}。
     *
     * <p>{@code amount}(2026-07-27 追加)は {@code quality} の直後に置く。{@code amount} と
     * {@code player} は同じ位置に並ぶ兄弟だが、前者が整数・後者が単語なので Brigadier が
     * 型で判別できる — <b>整数側を先に登録している</b>のはそのため(順序が逆だと "5" が
     * プレイヤー名として先に一致してしまう)。既存の呼び出し形はすべてそのまま動く。
     */
    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("give")
                .then(Commands.argument("item", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String remaining = builder.getRemainingLowerCase();
                            for (String id : suggestableIds()) {
                                if (remaining.isEmpty() || id.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                                    builder.suggest(id);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> giveTo(ctx.getSource(), null,
                                StringArgumentType.getString(ctx, "item"), quality.giveDefaultQuality(), 1))
                        .then(Commands.argument("quality",
                                        IntegerArgumentType.integer(ItemData.MIN_QUALITY, ItemData.MAX_QUALITY))
                                .executes(ctx -> giveTo(ctx.getSource(), null,
                                        StringArgumentType.getString(ctx, "item"),
                                        IntegerArgumentType.getInteger(ctx, "quality"), 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
                                        .executes(ctx -> giveTo(ctx.getSource(), null,
                                                StringArgumentType.getString(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "quality"),
                                                IntegerArgumentType.getInteger(ctx, "amount")))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests((ctx, builder) -> suggestPlayers(builder))
                                                .executes(ctx -> giveTo(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "item"),
                                                        IntegerArgumentType.getInteger(ctx, "quality"),
                                                        IntegerArgumentType.getInteger(ctx, "amount")))))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestPlayers(builder))
                                        .executes(ctx -> giveTo(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "quality"), 1))))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestPlayers(builder))
                                .executes(ctx -> giveTo(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "item"),
                                        quality.giveDefaultQuality(), 1))));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
    suggestPlayers(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (remaining.isEmpty() || p.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(p.getName());
            }
        }
        return builder.buildFuture();
    }

    private Set<String> suggestableIds() {
        Set<String> ids = new LinkedHashSet<>(catalog.all().keySet());
        try {
            ids.addAll(ArsItemGiveBridge.listPlayerItemIds());
        } catch (LinkageError | RuntimeException ex) {
            plugin.getLogger().log(Level.FINE, "Ars item ids unavailable for tab-complete", ex);
        }
        return ids;
    }

    private int giveTo(CommandSourceStack source, String playerName, String itemId, int requestedQuality,
                       int requestedAmount) {
        CommandSender sender = source.getSender();
        Player target;
        if (playerName == null || playerName.isBlank()) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(Component.text(
                        "Console: /trinityforge give <item> [quality] [amount] <player>", NamedTextColor.RED));
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

        if (ArsItemGiveBridge.isApparatus(itemId)) {
            sender.sendMessage(Component.text(
                    "装置系は /ars give " + itemId + " を使ってください。", NamedTextColor.RED));
            return 0;
        }

        int quality = Math.max(ItemData.MIN_QUALITY, Math.min(requestedQuality, this.quality.maxQuality()));
        int amount = Math.max(1, Math.min(requestedAmount, MAX_AMOUNT));
        ItemStack stack;
        String sourceLabel = "catalog";

        // Resolution order preserved exactly (behaviour-unchanged refactor onto CrossPluginItemResolver):
        // ArsPaper's registry is tried first (a give command has always favoured Ars-authored player
        // items — spellbooks/catalysts/materials — over a same-named TF catalog entry), then the TF
        // catalog. No vanilla-Material fallback here: an unresolvable id must still error out below,
        // matching pre-refactor behaviour (unlike the unified CrossPluginItemResolver#create used by
        // other callers such as GachaListener).
        Optional<ItemStack> ars = CrossPluginItemResolver.createArs(itemId);
        if (ars.isPresent()) {
            stack = ars.get();
            sourceLabel = "arspaper";
            // Catalysts/spellbooks (isQualityStamped) and equipment-tier materials get TF quality.
            boolean stampable = ArsItemGiveBridge.isQualityStamped(itemId)
                    || MaterialTier.of(stack.getType()).isEquipment();
            if (stampable) {
                try {
                    factory.stamp(stack, ThreadLocalRandom.current().nextLong(), quality);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to stamp Ars item '" + itemId + "' quality=" + quality, ex);
                    sender.sendMessage(Component.text(
                            "Item quality stamp failed; see console for details.", NamedTextColor.RED));
                    return 0;
                }
            }
        } else {
            Optional<ItemStack> built = resolver.createCatalog(
                    itemId, ThreadLocalRandom.current().nextLong(), quality);
            if (built.isEmpty()) {
                sender.sendMessage(Component.text("Unknown item id: " + itemId
                        + " (items/catalog.yml or ArsPaper registry).", NamedTextColor.RED));
                return 0;
            }
            stack = built.get();
        }

        // 2026-07-27 amount 対応。スタック不可の品(装備など)は1個ずつ組み直す — 品質/厳選ロールは
        // アイテムごとに独立していなければならず、setAmount で増やすと同一ロールの複製になってしまう。
        // スタック可能な品(素材・スクラップ等)はロールを持たないので、プロトタイプを最大スタック単位に
        // 割って配る(2304個で2304回の組み立てを走らせない)。
        List<ItemStack> toDeliver = new ArrayList<>();
        int maxStack = Math.max(1, stack.getMaxStackSize());
        if (maxStack <= 1) {
            toDeliver.add(stack);
            for (int i = 1; i < amount; i++) {
                ItemStack extra = buildOne(sender, itemId, quality);
                if (extra == null) {
                    return 0; // 途中失敗はメッセージ済み。ここまでに作った分は配らない(全か無か)。
                }
                toDeliver.add(extra);
            }
        } else {
            int remaining = amount;
            while (remaining > 0) {
                ItemStack part = stack.clone();
                int partAmount = Math.min(remaining, maxStack);
                part.setAmount(partAmount);
                toDeliver.add(part);
                remaining -= partAmount;
            }
        }

        boolean dropped = false;
        for (ItemStack delivery : toDeliver) {
            Map<Integer, ItemStack> leftover = target.getInventory().addItem(delivery);
            for (ItemStack overflow : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), overflow);
                dropped = true;
            }
        }
        if (dropped) {
            sender.sendMessage(Component.text(
                    "Inventory full — surplus dropped at " + target.getName() + "'s feet.",
                    NamedTextColor.YELLOW));
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                "<green>Gave <white><n></white>x <white><id></white> (<src>) q=<white><q></white> to <white><p></white>.",
                Placeholder.unparsed("n", Integer.toString(amount)),
                Placeholder.unparsed("id", itemId),
                Placeholder.unparsed("src", sourceLabel),
                Placeholder.unparsed("q", Integer.toString(quality)),
                Placeholder.unparsed("p", target.getName())));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@link #giveTo} と同じ解決順序で1個だけ組み立てる(スタック不可の品を複数個配るときの2個目以降)。
     * 解決/刻印に失敗したら送信者へ通知して {@code null} を返す。
     */
    private ItemStack buildOne(CommandSender sender, String itemId, int quality) {
        Optional<ItemStack> ars = CrossPluginItemResolver.createArs(itemId);
        if (ars.isPresent()) {
            ItemStack built = ars.get();
            boolean stampable = ArsItemGiveBridge.isQualityStamped(itemId)
                    || MaterialTier.of(built.getType()).isEquipment();
            if (stampable) {
                try {
                    factory.stamp(built, ThreadLocalRandom.current().nextLong(), quality);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to stamp Ars item '" + itemId + "' quality=" + quality, ex);
                    sender.sendMessage(Component.text(
                            "Item quality stamp failed; see console for details.", NamedTextColor.RED));
                    return null;
                }
            }
            return built;
        }
        Optional<ItemStack> built = resolver.createCatalog(
                itemId, ThreadLocalRandom.current().nextLong(), quality);
        if (built.isEmpty()) {
            sender.sendMessage(Component.text("Unknown item id: " + itemId
                    + " (items/catalog.yml or ArsPaper registry).", NamedTextColor.RED));
            return null;
        }
        return built.get();
    }
}
