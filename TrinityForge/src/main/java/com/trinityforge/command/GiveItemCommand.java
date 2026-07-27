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

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * {@code /trinityforge give <item> [quality] [player]} — catalog items and ArsPaper player items
 * (spellbooks/materials/…). Apparatus (pedestal/jars/sourcelinks) stay on {@code /ars give}.
 */
public final class GiveItemCommand {

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
                                StringArgumentType.getString(ctx, "item"), quality.giveDefaultQuality()))
                        .then(Commands.argument("quality",
                                        IntegerArgumentType.integer(ItemData.MIN_QUALITY, ItemData.MAX_QUALITY))
                                .executes(ctx -> giveTo(ctx.getSource(), null,
                                        StringArgumentType.getString(ctx, "item"),
                                        IntegerArgumentType.getInteger(ctx, "quality")))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestPlayers(builder))
                                        .executes(ctx -> giveTo(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "quality")))))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestPlayers(builder))
                                .executes(ctx -> giveTo(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "item"),
                                        quality.giveDefaultQuality()))));
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

    private int giveTo(CommandSourceStack source, String playerName, String itemId, int requestedQuality) {
        CommandSender sender = source.getSender();
        Player target;
        if (playerName == null || playerName.isBlank()) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(Component.text(
                        "Console: /trinityforge give <item> [quality] <player>", NamedTextColor.RED));
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

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover.values().iterator().next());
            sender.sendMessage(Component.text(
                    "Inventory full — item dropped at " + target.getName() + "'s feet.", NamedTextColor.YELLOW));
            return Command.SINGLE_SUCCESS;
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                "<green>Gave <white><id></white> (<src>) q=<white><q></white> to <white><p></white>.",
                Placeholder.unparsed("id", itemId),
                Placeholder.unparsed("src", sourceLabel),
                Placeholder.unparsed("q", Integer.toString(quality)),
                Placeholder.unparsed("p", target.getName())));
        return Command.SINGLE_SUCCESS;
    }
}
