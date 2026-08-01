package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
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
import java.util.function.ToIntFunction;
import java.util.logging.Level;

/**
 * {@code /trinityforge give <item> [quality] [amount <n>] [player]} — catalog items and ArsPaper player
 * items (spellbooks/materials/…). Apparatus (pedestal/jars/sourcelinks) stay on {@code /ars give}.
 *
 * <p><b>個数の指定は {@code amount} リテラル経由が正</b>(2026-08-01)。位置指定の第2引数は昔から
 * {@code quality} なので、{@code /tf give iron_ingot 64} は「64個」ではなく「品質64」と解釈される
 * ——しかも実効上限({@link QualityConfig#maxQuality()}、既定9)へ黙って丸められて「品質9が1個」に
 * なっていた。数量を指定する手段が事実上無かったのはこれが理由。位置指定形
 * ({@code /tf give <item> <quality> <count>}) は後方互換のため残してある。
 */
public final class GiveItemCommand {

    /** {@code amount} の下限。0個・負数の要求は必ずエラーにする(黙って1個に読み替えない)。 */
    static final int MIN_AMOUNT = 1;

    /**
     * {@code amount} の上限。プレイヤーインベントリ36枠 × 最大スタック64 = 2304 個で、
     * 「1回で持ちきれる最大」を超えない。これ以上はどう転んでも足元へ落ちるだけなので受け付けない
     * (装備のように1個ずつ組み立てる品では、大きな値がそのまま生成回数になるため上限は必須)。
     */
    static final int MAX_AMOUNT = 2304;

    /**
     * スタックできない品(装備など)の上限。1個ずつ独立ロールで組み立てるため、指定数がそのまま
     * {@code factory.stamp} の実行回数になる。メインスレッドで数千回まわすのは現実的でないので、
     * 「1回のコマンドで手渡しうる量」として1インベントリ枠数ぶんに抑える。
     */
    static final int MAX_UNSTACKABLE_AMOUNT = 36;

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
     * {@code /tf give <item> [quality] [amount <n>] [player]}。
     *
     * <p>Brigadier は同じ入力トークンに対して<b>リテラル一致を引数一致より常に優先する</b>
     * ({@code CommandNode#getRelevantNodes} がリテラル辞書を先に引き、当たればそのリテラル1本だけを
     * 返す)ため、{@code amount} リテラルは兄弟の {@code quality}(整数)や {@code player}(単語)と
     * 衝突しない。逆に副作用として <b>{@code amount} という名前のプレイヤーはこの位置で指定できない</b>
     * — 実運用で衝突しない語として選んでいる。{@code StatsCommand#detailNode()} と同じ配線規約。
     *
     * <p>整数の兄弟同士({@code quality} と位置指定の {@code count})は型で判別できないので
     * <b>登録順が解決順</b>になる。{@code count} を {@code player} より先に登録しているのはそのため
     * (順序が逆だと "5" がプレイヤー名として先に一致してしまう)。
     *
     * <p><b>個数の引数名を {@code amount} でなく {@code count} にしてあるのは必須</b>:
     * Brigadier の {@code CommandNode#addChild} は子を<b>名前をキーにした1本のマップ</b>で持ち、
     * 同名の子が既にいると「マージ」して<b>後から足したノードを無言で捨てる</b>。リテラル
     * {@code amount} と整数引数 {@code amount} を同じ親({@code quality} ノード)に並べると、
     * 後者が前者へ吸収されて位置指定形 {@code /tf give <item> <品質> <個数>} が丸ごと消え、
     * 個数が {@code player} 引数へ流れる(実際にこの実装で踏んで
     * {@code GiveItemCommandAmountTest} が検出した)。
     *
     * <p>{@code quality} の Brigadier 側の範囲は {@link ItemData} の静的な 0〜100 に固定し、
     * <b>設定の実効上限は実行時に検証する</b>({@link #validateRanges})。コマンドツリーは起動時に
     * 一度だけ登録されるので、ここに設定値を焼き込むと {@code /tf reload} 後に stale な範囲が残るため。
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
                        .executes(ctx -> giveTo(ctx.getSource().getSender(), null,
                                StringArgumentType.getString(ctx, "item"), quality.giveDefaultQuality(), 1))
                        // 品質を書かずに個数だけ指定する形。既定品質を使う。
                        .then(amountNode(ctx -> quality.giveDefaultQuality()))
                        .then(Commands.argument("quality",
                                        IntegerArgumentType.integer(ItemData.MIN_QUALITY, ItemData.MAX_QUALITY))
                                .executes(ctx -> giveTo(ctx.getSource().getSender(), null,
                                        StringArgumentType.getString(ctx, "item"),
                                        IntegerArgumentType.getInteger(ctx, "quality"), 1))
                                // 品質と個数を両方指定する形。キーワード付き(推奨)と位置指定(後方互換)の両方を張る。
                                .then(amountNode(ctx -> IntegerArgumentType.getInteger(ctx, "quality")))
                                .then(Commands.argument("count",
                                                IntegerArgumentType.integer(MIN_AMOUNT, MAX_AMOUNT))
                                        .executes(ctx -> giveTo(ctx.getSource().getSender(), null,
                                                StringArgumentType.getString(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "quality"),
                                                IntegerArgumentType.getInteger(ctx, "count")))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests((ctx, builder) -> suggestPlayers(builder))
                                                .executes(ctx -> giveTo(ctx.getSource().getSender(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "item"),
                                                        IntegerArgumentType.getInteger(ctx, "quality"),
                                                        IntegerArgumentType.getInteger(ctx, "count")))))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestPlayers(builder))
                                        .executes(ctx -> giveTo(ctx.getSource().getSender(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "quality"), 1))))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestPlayers(builder))
                                .executes(ctx -> giveTo(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "item"),
                                        quality.giveDefaultQuality(), 1))));
    }

    /**
     * {@code amount <n> [player]} 枝。品質の決め方だけが呼び出し位置で変わるので {@code qualityOf}
     * で注入する(アイテム直下 = 既定品質 / {@code quality} 引数の直下 = その値)。
     */
    private LiteralArgumentBuilder<CommandSourceStack> amountNode(
            ToIntFunction<CommandContext<CommandSourceStack>> qualityOf) {
        return Commands.literal("amount")
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(Component.text(
                            "使い方: /tf give <item> [品質] amount <個数> [プレイヤー]", NamedTextColor.RED));
                    return 0;
                })
                .then(Commands.argument("count", IntegerArgumentType.integer(MIN_AMOUNT, MAX_AMOUNT))
                        .executes(ctx -> giveTo(ctx.getSource().getSender(), null,
                                StringArgumentType.getString(ctx, "item"),
                                qualityOf.applyAsInt(ctx),
                                IntegerArgumentType.getInteger(ctx, "count")))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestPlayers(builder))
                                .executes(ctx -> giveTo(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "item"),
                                        qualityOf.applyAsInt(ctx),
                                        IntegerArgumentType.getInteger(ctx, "count")))));
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

    /**
     * 品質と個数の範囲検証。範囲外なら送信者へ出すエラー文、問題なければ {@code null} を返す。
     *
     * <p><b>無言クランプは禁止</b>: 「指定した値と実挙動がずれる」事故をこのコードベースで繰り返して
     * いるので、範囲外は必ずエラーにして<b>1個も配らない</b>。とくに品質は Brigadier 側の範囲
     * (0〜100)より設定の実効上限({@link QualityConfig#maxQuality()}、既定9)のほうが狭く、旧実装では
     * ここが黙って丸められていた ——「個数のつもりで打った数字が品質として丸められる」のが
     * 「個数を指定できない」の正体なので、エラー文で {@code amount} の書き方を必ず案内する。
     */
    static String validateRanges(int requestedQuality, int requestedAmount, int maxQuality) {
        if (requestedQuality < ItemData.MIN_QUALITY || requestedQuality > maxQuality) {
            return "品質は " + ItemData.MIN_QUALITY + "〜" + maxQuality + " の範囲で指定してください"
                    + "(指定値: " + requestedQuality + ")。"
                    + " 個数を指定したい場合は /tf give <item> amount <個数> です。";
        }
        if (requestedAmount < MIN_AMOUNT || requestedAmount > MAX_AMOUNT) {
            return "個数は " + MIN_AMOUNT + "〜" + MAX_AMOUNT + " の範囲で指定してください"
                    + "(指定値: " + requestedAmount + ")。";
        }
        return null;
    }

    /**
     * スタック不可の品(装備など)の個数上限チェック。超過なら理由文、問題なければ {@code null}。
     *
     * <p>丸めずに<b>弾く</b>: 装備は1個ずつ独立ロールで組み立てるので、「37個要求 → 36個渡した」を
     * 警告だけ出して通すと、指定値と手元の数が食い違ったまま作業が進んでしまう(旧実装はこれを
     * やっていた)。上限自体を超える要求はコマンドごと失敗させ、1個も配らない。
     */
    static String unstackableRejection(int requestedAmount) {
        if (requestedAmount <= MAX_UNSTACKABLE_AMOUNT) {
            return null;
        }
        return "スタックできない品(装備など)は1個ずつ個別ロールで生成するため、一度に "
                + MAX_UNSTACKABLE_AMOUNT + " 個までです(指定値: " + requestedAmount + ")。";
    }

    /**
     * スタック可能な品を最大スタック単位へ分割する。<b>上限で弾かずに分割する</b>のは、素材を
     * 「64個ちょうど」渡したいだけの用途が主で、分割しても意味が変わらないため(ロールを持たない品
     * なのでプロトタイプの複製で等価)。装備側を同じ理屈で誤魔化さないのは
     * {@link #unstackableRejection} のとおり。
     */
    static List<ItemStack> splitIntoStacks(ItemStack prototype, int amount) {
        int maxStack = Math.max(1, prototype.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            ItemStack part = prototype.clone();
            int partAmount = Math.min(remaining, maxStack);
            part.setAmount(partAmount);
            stacks.add(part);
            remaining -= partAmount;
        }
        return stacks;
    }

    // CommandSourceStack でなく CommandSender を受け取る(パッケージ非公開)。Paper の
    // CommandSourceStack は MockBukkit 環境で組み立てにくく、ここを CommandSender の受け渡しに
    // 留めることで GiveItemCommandAmountTest が Brigadier のプラミング無しに直接呼び出せる
    // (StatsCommand#show/detail と同じ「テスト容易性のためのパッケージ非公開化」規約)。
    int giveTo(CommandSender sender, String playerName, String itemId, int requestedQuality,
               int requestedAmount) {
        // 引数の範囲検証はワールド状態(対象プレイヤーの在席)より先に行う。範囲外はコマンド自体の
        // 誤りで「誰に渡すか」に依存しないため、対象解決の成否でメッセージが変わらないようにする。
        String rangeError = validateRanges(requestedQuality, requestedAmount, quality.maxQuality());
        if (rangeError != null) {
            sender.sendMessage(Component.text(rangeError, NamedTextColor.RED));
            return 0;
        }

        Player target = resolveTarget(sender, playerName);
        if (target == null) {
            return 0; // 理由は送信済み。
        }

        if (ArsItemGiveBridge.isApparatus(itemId)) {
            sender.sendMessage(Component.text(
                    "装置系は /ars give " + itemId + " を使ってください。", NamedTextColor.RED));
            return 0;
        }

        Built first = buildOne(sender, itemId, requestedQuality);
        if (first == null) {
            return 0; // 解決/刻印の失敗はメッセージ送信済み。
        }

        // スタック不可の品(装備など)は1個ずつ組み直す — 品質/厳選ロールはアイテムごとに独立して
        // いなければならず、setAmount で増やすと同一ロールの複製になってしまう。スタック可能な品
        // (素材・スクラップ等)はロールを持たないので、プロトタイプを最大スタック単位に割って配る
        // (2304個で2304回の組み立てを走らせない)。
        List<ItemStack> toDeliver = new ArrayList<>();
        if (first.stack().getMaxStackSize() <= 1) {
            String rejection = unstackableRejection(requestedAmount);
            if (rejection != null) {
                sender.sendMessage(Component.text(rejection, NamedTextColor.RED));
                return 0;
            }
            toDeliver.add(first.stack());
            for (int i = 1; i < requestedAmount; i++) {
                Built extra = buildOne(sender, itemId, requestedQuality);
                if (extra == null) {
                    return 0; // 途中失敗はメッセージ済み。ここまでに作った分は配らない(全か無か)。
                }
                toDeliver.add(extra.stack());
            }
        } else {
            toDeliver.addAll(splitIntoStacks(first.stack(), requestedAmount));
        }

        if (deliverAll(target, toDeliver)) {
            sender.sendMessage(Component.text(
                    "Inventory full — surplus dropped at " + target.getName() + "'s feet.",
                    NamedTextColor.YELLOW));
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                "<green>Gave <white><n></white>x <white><id></white> (<src>) q=<white><q></white> to <white><p></white>.",
                Placeholder.unparsed("n", Integer.toString(requestedAmount)),
                Placeholder.unparsed("id", itemId),
                Placeholder.unparsed("src", first.sourceLabel()),
                Placeholder.unparsed("q", Integer.toString(requestedQuality)),
                Placeholder.unparsed("p", target.getName())));
        return Command.SINGLE_SUCCESS;
    }

    /** 受取先の解決。解決できなければ理由を送信して {@code null} を返す。 */
    private static Player resolveTarget(CommandSender sender, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(Component.text(
                        "Console: /trinityforge give <item> [quality] [amount <n>] <player>",
                        NamedTextColor.RED));
                return null;
            }
            return self;
        }
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(Component.text("Player not online: " + playerName, NamedTextColor.RED));
            // 「個数のつもりで数字を打った」ケースの救済。品質の範囲(0〜100)を外れた数字は
            // player 引数へ流れ着くので、ここが実質「個数を打った人」の着地点になる。
            if (playerName.chars().allMatch(Character::isDigit)) {
                sender.sendMessage(Component.text(
                        "個数を指定したい場合は /tf give <item> amount " + playerName + " です。",
                        NamedTextColor.YELLOW));
            }
        }
        return target;
    }

    /**
     * 実際の受け渡し。溢れた分を足元へ落としたら {@code true}。満杯時に拒否せず地面へ落とすのは
     * 従来からの挙動で、変えていない(この配布経路は元々「必ず渡し切る」前提で使われている)。
     */
    private static boolean deliverAll(Player target, List<ItemStack> stacks) {
        boolean dropped = false;
        for (ItemStack delivery : stacks) {
            Map<Integer, ItemStack> leftover = target.getInventory().addItem(delivery);
            for (ItemStack overflow : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), overflow);
                dropped = true;
            }
        }
        return dropped;
    }

    /** 組み立て結果。{@code sourceLabel} は完了メッセージに出す出所表示。 */
    private record Built(ItemStack stack, String sourceLabel) {}

    /**
     * アイテムを1個だけ組み立てる。<b>解決経路はここ1箇所にしかない</b> — 複数個配るときの
     * 2個目以降と1個目で解決順序がずれると、同じコマンドで別物が混ざりうるため。
     * 解決/刻印に失敗したら送信者へ通知して {@code null} を返す。
     *
     * <p>解決順序は Ars レジストリ→TFカタログで固定(give は昔から Ars 製のプレイヤーアイテム —
     * 魔導書/触媒/素材 — を同名のTFカタログより優先してきた)。バニラ Material への
     * フォールバックは<b>置かない</b>: 解決できない id はここでエラーにする必要がある
     * (GachaListener 等が使う統合版 {@code CrossPluginItemResolver#create} とはそこが違う)。
     */
    private Built buildOne(CommandSender sender, String itemId, int quality) {
        Optional<ItemStack> ars = CrossPluginItemResolver.createArs(itemId);
        if (ars.isPresent()) {
            ItemStack built = ars.get();
            // Catalysts/spellbooks (isQualityStamped) and equipment-tier materials get TF quality.
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
            return new Built(built, "arspaper");
        }
        Optional<ItemStack> built = resolver.createCatalog(
                itemId, ThreadLocalRandom.current().nextLong(), quality);
        if (built.isEmpty()) {
            sender.sendMessage(Component.text("Unknown item id: " + itemId
                    + " (items/catalog.yml or ArsPaper registry).", NamedTextColor.RED));
            return null;
        }
        return new Built(built.get(), "catalog");
    }
}
