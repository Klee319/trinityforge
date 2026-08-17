package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.CollectionEntryNames;
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
import java.util.logging.Logger;

/**
 * {@code /trinityforge give <item> [quality] [amount <n>] [player]} — catalog items and ArsPaper player
 * items (spellbooks/materials/…). Apparatus (pedestal/jars/sourcelinks) stay on {@code /ars give}.
 *
 * <p><b>個数の指定は {@code amount} リテラル経由が正</b>(2026-08-01)。位置指定の第2引数は昔から
 * {@code quality} なので、{@code /tf give iron_ingot 64} は「64個」ではなく「品質64」と解釈される
 * ——しかも実効上限({@link QualityConfig#maxQuality()})へ黙って丸められて「品質が1個」になっていた。
 * 数量を指定する手段が事実上無かったのはこれが理由。位置指定形
 * ({@code /tf give <item> <quality> <count>}) は後方互換のため残してある。
 *
 * <p><b>品質の実効上限をこのクラスに焼き込まないこと</b>(2026-08-01 追記)。上限は
 * {@link QualityConfig#maxQuality()} が返す値で、しかも <b>{@code stats/quality.yml} の
 * {@code max-quality} ではなく {@code stats/quality-tiers.yml} のティア数-1 が優先される</b>。
 * 出荷 config はティアが16行あるので実効上限は <b>15</b> であり、yml に書いてある {@code max-quality: 9}
 * でも、かつてここの javadoc に書いてあった「既定9」でもない。つまり
 * {@code /tf give iron_ingot 15}(「15個ほしい」のつもり)は<b>今も</b>エラーにならず「品質15が1個」に
 * なる —— 範囲外に落ちたときだけ救済メッセージが出せるので、上限を小さく誤認していると
 * 救済が働く範囲まで誤認することになる。数値は必ず実行時に config から引く。
 */
public final class GiveItemCommand {

    /**
     * 個数指定のキーワード。<b>照合は大文字小文字を無視する</b>({@link #isAmountKeyword}) —
     * Brigadier のリテラルは完全一致でしか当たらず(親ノードの {@code literals} が名前をキーにした
     * マップなので、{@code Amount} は<b>リテラル候補にすら上がらず</b>そのまま {@code player} 引数へ
     * 流れる)、統合版/スマホのキーボードは先頭を自動で大文字にするため、素直に書くと
     * {@code /tf give iron_ingot Amount 64} が「Amount というプレイヤーへ」の意味に化ける。
     *
     * <p>綴り違いのリテラルノードを追加で登録する解決策は採らない: <b>リテラルの補完はクライアント側が
     * 受け取ったコマンドツリーから行う</b>ので、サーバ側で {@code listSuggestions} を潰しても
     * タブ補完には全綴りが並んでしまう。代わりに {@code player} 引数の着地点で判定する
     * ({@link #givePlayerOrKeyword} / {@link #giveWordThenCount})。
     */
    static final String AMOUNT_KEYWORD = "amount";

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
    private final ThreadQualityRestamper threadRestamper;

    public GiveItemCommand(Plugin plugin, ItemFactory factory, ItemCatalogConfig catalog,
                           QualityConfig quality) {
        this(plugin, factory, catalog, quality, GiveItemCommand::defaultThreadRestamp);
    }

    /**
     * テスト用シーム。{@code threadRestamper} は「ArsPaper のスレッド(ThreadItem)専用の品質再刻印」を
     * 行う関数で、本番は必ず {@link #defaultThreadRestamp} が入る(4引数コンストラクタ)。
     *
     * <p>差し替え可能にしてあるのは、本番実装が {@code Bukkit.getPluginManager().getPlugin("ArsPaper")}
     * 越しのリフレクションで、ユニットテストからは実際の ArsPaper プラグイン(と ThreadItem の実体)を
     * 用意できないため({@link com.trinityforge.stats.CrossPluginItemResolver} が同じ理由で
     * 外部解決関数を差し替え可能にしているのと同じ制約)。
     */
    GiveItemCommand(Plugin plugin, ItemFactory factory, ItemCatalogConfig catalog,
                    QualityConfig quality, ThreadQualityRestamper threadRestamper) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.resolver = new CrossPluginItemResolver(catalog, factory);
        this.threadRestamper = Objects.requireNonNull(threadRestamper, "threadRestamper");
    }

    /**
     * ArsPaper のスレッド(ThreadItem)専用の品質再刻印関数。{@code itemId} が実際にスレッドで、
     * かつ品質を持つ(={@code threadType.hasEffect()})場合にのみ再刻印し {@code true} を返す。
     * それ以外(スレッドでない/ArsPaper未ロード/reflection失敗)は {@code false}
     * (呼び出し側は既存の {@code isQualityStamped}/{@code isEquipment} ゲートへフォールバックする)。
     */
    @FunctionalInterface
    interface ThreadQualityRestamper {
        boolean restamp(String itemId, ItemStack item, int quality);
    }

    /**
     * {@link ThreadQualityRestamper} の本番実装。ArsPaper 側 {@code ThreadItem#restampWithQuality}
     * (メソッド名/シグネチャは合わせて変更する契約)へ reflection 経由で委譲する。
     *
     * <p><b>なぜ必要か(W-53)</b>: ArsPaper のスレッドは {@code isQualityStamped()==false}
     * (装備ではなく素材扱い)かつ非装備材質(防具トリム型/陶器の欠片/旗の模様)なので、
     * 下の {@link #stampArsItem} が持つ通常ゲート(isQualityStamped || isEquipment)に一切
     * 引っかからず、{@code /tf give} で指定した quality が常に無視されていた
     * (既知の「品質の門を {@code isEquipment()} で書くと非装備TF品が常に品質0になる」落とし穴と同型
     * ── {@link com.trinityforge.listeners.CraftQualityListener#isStampableCraftResult} が
     * クラフト経路で同型の穴を塞いだのと同じ形)。
     *
     * <p>{@code ItemFactory#stamp}(TFの汎用装備lore組み立て経路)をスレッドへそのまま適用すると、
     * 効果説明/スロット案内/バックパック行を含むスレッド専用loreを上書きしてしまうため、ここでは
     * 汎用stampを一切呼ばず、ArsPaper側の専用経路(スレッド自身の lore ビルダーを使う
     * {@code restampWithQuality})へ委譲する。
     */
    private static boolean defaultThreadRestamp(String itemId, ItemStack item, int quality) {
        if (!ArsItemGiveBridge.isAvailable()) {
            return false;
        }
        try {
            Plugin ars = Bukkit.getPluginManager().getPlugin("ArsPaper");
            if (ars == null) {
                return false;
            }
            Object registry = ars.getClass().getMethod("getItemRegistry").invoke(ars);
            Object opt = registry.getClass().getMethod("get", String.class).invoke(registry, itemId);
            if (!(opt instanceof Optional<?> optional) || optional.isEmpty()) {
                return false;
            }
            Object customItem = optional.get();
            java.lang.reflect.Method restamp;
            try {
                restamp = customItem.getClass().getMethod("restampWithQuality", ItemStack.class, int.class);
            } catch (NoSuchMethodException notAThread) {
                // ThreadItem 以外(魔導書/触媒/素材等): 対象外。呼び出し側が既存ゲートへフォールバックする。
                return false;
            }
            return (boolean) restamp.invoke(customItem, item, quality);
        } catch (ReflectiveOperationException ex) {
            Logger.getLogger(GiveItemCommand.class.getName())
                    .log(Level.FINE, "[give] ArsPaper thread quality restamp failed for " + itemId, ex);
            return false;
        }
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
     *
     * <p><b>個数({@code count})の Brigadier 側の範囲は意図的に無制限</b>(2026-08-01)。
     * {@code integer(MIN_AMOUNT, MAX_AMOUNT)} で縛ると Brigadier が先に弾いてしまい、
     * {@link #validateRanges} の個数側エラーが<b>本番から到達不能な死に分岐</b>になっていた
     * (テストだけが直接呼んで「担保しているつもり」になる)。範囲外は自前の日本語エラーで返したいので、
     * パーサは通して実行時に検証する ——品質と同じ規約。
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
                                .then(Commands.argument("count", IntegerArgumentType.integer())
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
                                .then(playerNode(ctx -> IntegerArgumentType.getInteger(ctx, "quality"))))
                        .then(playerNode(ctx -> quality.giveDefaultQuality())));
    }

    /**
     * {@code amount <n> [player]} 枝。品質の決め方だけが呼び出し位置で変わるので {@code qualityOf}
     * で注入する(アイテム直下 = 既定品質 / {@code quality} 引数の直下 = その値)。
     */
    private LiteralArgumentBuilder<CommandSourceStack> amountNode(
            ToIntFunction<CommandContext<CommandSourceStack>> qualityOf) {
        return Commands.literal(AMOUNT_KEYWORD)
                .executes(ctx -> {
                    sendAmountUsage(ctx.getSource().getSender());
                    return 0;
                })
                .then(Commands.argument("count", IntegerArgumentType.integer())
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

    /**
     * {@code <player> [<n> [player]]} 枝。{@code player} は素の単語なので、<b>綴りが
     * {@code amount} と大文字小文字だけ違うトークンはここへ落ちてくる</b>(理由は
     * {@link #AMOUNT_KEYWORD})。そのケースを黙ってプレイヤー名として扱わないために、
     * この枝に「単語のあとに個数」の形を生やして着地点で判定する。
     *
     * <p>副産物として {@code /tf give <item> Steve 64}(プレイヤー名の後ろに個数)も
     * Brigadier の構文エラーではなく<b>日本語の案内</b>で返せるようになる。整数の兄弟
     * ({@code quality} / 位置指定 {@code count})のほうが先に登録されているので、
     * {@code /tf give <item> 5 64} の解決先は従来どおり品質+個数のまま変わらない
     * (Brigadier は同点の候補を登録順の安定ソートで選ぶ)。
     */
    private RequiredArgumentBuilder<CommandSourceStack, String> playerNode(
            ToIntFunction<CommandContext<CommandSourceStack>> qualityOf) {
        return Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, builder) -> suggestPlayers(builder))
                .executes(ctx -> givePlayerOrKeyword(ctx.getSource().getSender(),
                        StringArgumentType.getString(ctx, "player"),
                        StringArgumentType.getString(ctx, "item"),
                        qualityOf.applyAsInt(ctx)))
                .then(Commands.argument("count", IntegerArgumentType.integer())
                        .executes(ctx -> giveWordThenCount(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "player"), null,
                                StringArgumentType.getString(ctx, "item"),
                                qualityOf.applyAsInt(ctx),
                                IntegerArgumentType.getInteger(ctx, "count")))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestPlayers(builder))
                                .executes(ctx -> giveWordThenCount(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "target"),
                                        StringArgumentType.getString(ctx, "item"),
                                        qualityOf.applyAsInt(ctx),
                                        IntegerArgumentType.getInteger(ctx, "count")))));
    }

    /** {@code amount} キーワードの照合(大文字小文字・前後の空白を無視)。 */
    static boolean isAmountKeyword(String token) {
        return token != null && AMOUNT_KEYWORD.equalsIgnoreCase(token.trim());
    }

    private static void sendAmountUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "使い方: /tf give <item> [品質] amount <個数> [プレイヤー]", NamedTextColor.RED));
    }

    /**
     * {@code player} 位置に落ちた単語の着地点。{@code amount} の綴り違い(大文字小文字)なら
     * 個数の書き方を案内する —— ここを素通しすると「Amount というプレイヤーは居ません」という
     * 見当違いのエラーになり、個数指定ができないという元の症状に戻る。
     */
    private int givePlayerOrKeyword(CommandSender sender, String word, String itemId, int requestedQuality) {
        if (isAmountKeyword(word)) {
            sendAmountUsage(sender);
            return 0;
        }
        return giveTo(sender, word, itemId, requestedQuality, 1);
    }

    /**
     * {@code <word> <n> [player]} の着地点。{@code word} が {@code amount} の綴り違いなら個数指定として
     * 受理し、そうでなければ「プレイヤー名の後ろに個数は置けない」と正しい形を案内する。
     */
    private int giveWordThenCount(CommandSender sender, String word, String trailingPlayer,
                                  String itemId, int requestedQuality, int requestedAmount) {
        if (isAmountKeyword(word)) {
            return giveTo(sender, trailingPlayer, itemId, requestedQuality, requestedAmount);
        }
        sender.sendMessage(Component.text(
                "プレイヤー名(" + word + ")の後ろに個数は置けません。"
                        + "個数は /tf give <item> [品質] amount <個数> [プレイヤー] の形で指定してください。",
                NamedTextColor.RED));
        return 0;
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
     * (0〜100)より設定の実効上限({@link QualityConfig#maxQuality()}、出荷 config では15)のほうが狭く、
     * 旧実装ではここが黙って丸められていた ——「個数のつもりで打った数字が品質として丸められる」のが
     * 「個数を指定できない」の正体なので、エラー文で {@code amount} の書き方を必ず案内する。
     * {@code maxQuality} は必ず呼び出し側が config から引いて渡すこと(ここに定数を置かない)。
     *
     * <p><b>個数側の分岐も本番から到達する</b>(2026-08-01 修正)。以前は Brigadier の
     * {@code integer(MIN_AMOUNT, MAX_AMOUNT)} が先に弾いていたためこの分岐は死んでおり、
     * 直接呼ぶテストだけが緑になっていた。現在は {@code count} をパーサ側で縛らないので、
     * {@code /tf give <item> amount 0} や {@code amount 99999} はここに到達して日本語で返る。
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

        // 表示名で返す。ID は運用上まだ知りたいので括弧で併記する(コマンド出力は管理者向け)。
        sender.sendMessage(MiniMessage.miniMessage().deserialize(
                "<green>Gave <white><n></white>x <white><name></white> <gray>(<id>)</gray> (<src>) q=<white><q></white> to <white><p></white>.",
                Placeholder.unparsed("n", Integer.toString(requestedAmount)),
                Placeholder.component("name", CollectionEntryNames.itemName(itemId, first.stack())),
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
            if (!playerName.isEmpty() && playerName.chars().allMatch(Character::isDigit)) {
                sender.sendMessage(Component.text(amountRescueHint(playerName), NamedTextColor.YELLOW));
            }
        }
        return target;
    }

    /**
     * 「個数のつもりで数字を打った」人への案内文。
     *
     * <p><b>打てないコマンドを案内しないこと</b>(2026-08-01 修正)。旧実装は拾った数字をそのまま
     * {@code amount <数字>} に埋め込んでいたので、{@code /tf give iron_ingot 99999} には
     * 「{@code /tf give iron_ingot amount 99999} です」と返していた —— それも個数の上限
     * ({@link #MAX_AMOUNT})に引っかかって失敗する。範囲内の値だけ埋め込み、範囲外(桁あふれ含む)は
     * 値を出さずに上限を伝える。
     */
    static String amountRescueHint(String digits) {
        int value;
        try {
            value = Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            value = -1; // 桁あふれ。範囲外と同じ扱い。
        }
        if (value >= MIN_AMOUNT && value <= MAX_AMOUNT) {
            return "個数を指定したい場合は /tf give <item> amount " + value + " です。";
        }
        return "個数を指定したい場合は /tf give <item> amount <個数> です"
                + "(個数は " + MIN_AMOUNT + "〜" + MAX_AMOUNT + ")。";
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

    /** 組み立て結果。{@code sourceLabel} は完了メッセージに出す出所表示。 package-private: テスト用。 */
    record Built(ItemStack stack, String sourceLabel) {}

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
        // draft(準備中)は give でも作らない。管理者コマンドだから素通しでよい、とはならない ──
        // アチーブメント/図鑑報酬の commands: に "tf give <draft-id>" と書けばプレイヤーへ渡るため。
        // 判定は CrossPluginItemResolver#createArsGated 1箇所に寄せてある。
        Optional<ItemStack> ars = resolver.createArsGated(itemId);
        if (ars.isPresent()) {
            return stampArsItem(sender, itemId, ars.get(), quality);
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

    /**
     * ArsPaper 側で既に解決済みのアイテム({@code built})へ {@code /tf give} の quality 引数を反映する。
     * package-private: {@link #resolver}(実 ArsPaper 有無に依存)を経由せず直接テストできるように
     * するため(本番の Ars 可用性判定は呼び出し元 {@link #buildOne} が既に済ませている)。
     *
     * <p>まず {@link #threadRestamper}(スレッド専用の再刻印)を試す。ArsPaper のスレッドは
     * {@code isQualityStamped()==false} かつ非装備材質なので、下の通常ゲート
     * ({@code isQualityStamped || isEquipment})に一切引っかからず quality が無視されていた
     * (W-53、{@link #defaultThreadRestamp} のjavadoc参照)。{@link #threadRestamper} が
     * {@code true}(=スレッドとして処理済み)を返したら、下の通常ゲートは一切通らない。
     *
     * @return 刻印(またはスレッド専用再刻印)に成功したら {@code built} を包んだ {@link Built}。
     *         失敗時は {@code sender} へ通知して {@code null}(呼び出し元はそのまま返す契約)。
     */
    Built stampArsItem(CommandSender sender, String itemId, ItemStack built, int quality) {
        boolean threadHandled;
        try {
            threadHandled = threadRestamper.restamp(itemId, built, quality);
        } catch (RuntimeException ex) {
            return stampFailed(sender, itemId, quality, ex);
        }
        if (!threadHandled) {
            // Catalysts/spellbooks (isQualityStamped) and equipment-tier materials get TF quality.
            boolean stampable = ArsItemGiveBridge.isQualityStamped(itemId)
                    || MaterialTier.of(built.getType()).isEquipment();
            if (stampable) {
                try {
                    factory.stamp(built, ThreadLocalRandom.current().nextLong(), quality);
                } catch (RuntimeException ex) {
                    return stampFailed(sender, itemId, quality, ex);
                }
            }
        }
        return new Built(built, "arspaper");
    }

    private Built stampFailed(CommandSender sender, String itemId, int quality, RuntimeException ex) {
        plugin.getLogger().log(Level.SEVERE,
                "Failed to stamp Ars item '" + itemId + "' quality=" + quality, ex);
        sender.sendMessage(Component.text(
                "Item quality stamp failed; see console for details.", NamedTextColor.RED));
        return null;
    }
}
