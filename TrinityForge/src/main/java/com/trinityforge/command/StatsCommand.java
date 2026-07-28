package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.stats.StatAppliesTo;
import com.trinityforge.stats.StatBound;
import com.trinityforge.stats.StatDisplaySpec;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatLimits;
import com.trinityforge.stats.StatTrigger;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@code /tf stats [attack|armor|craft|gathering|utility|ars|other|all]} — 装備・パーク・アドオン合算後の実効ステを表示する。
 * カテゴリ省略時は {@code all}。表示名は {@code stats/lore.yml}、数値は小数第3位以下切り捨て、
 * 単位は lore の format/unit に従う。
 */
public final class StatsCommand {

    private final SymmetricCombatService combatService;
    private final PlayerStatAggregator aggregator;
    private final LoreConfig loreConfig;
    private final SkillLevelSource skillLevelSource;

    public StatsCommand(SymmetricCombatService combatService,
                        PlayerStatAggregator aggregator,
                        LoreConfig loreConfig,
                        SkillLevelSource skillLevelSource) {
        this.combatService = Objects.requireNonNull(combatService, "combatService");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.loreConfig = Objects.requireNonNull(loreConfig, "loreConfig");
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        // "detail" はリテラル子ノード、"category" は同じ深さの引数子ノードだが、Brigadier は同じ入力
        // トークンに対してリテラル一致を引数一致より常に優先して解決するため、"/tf stats detail <key>"
        // が "category" 引数へ吸われることはない(StatsCommandDetailTest で回帰確認)。
        return Commands.literal("stats")
                .executes(ctx -> show(ctx.getSource().getSender(), StatsCategory.ALL))
                .then(detailNode())
                .then(Commands.argument("category", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String rem = builder.getRemainingLowerCase();
                            for (StatsCategory cat : StatsCategory.values()) {
                                String id = cat.id();
                                if (rem.isEmpty() || id.startsWith(rem)) {
                                    builder.suggest(id);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String raw = StringArgumentType.getString(ctx, "category");
                            StatsCategory cat = StatsCategory.parse(raw);
                            if (cat == null) {
                                ctx.getSource().getSender().sendMessage(Component.text(
                                        "カテゴリは attack / armor / craft / gathering / utility / ars / other"
                                                + " / all のいずれかです。",
                                        NamedTextColor.RED));
                                return 0;
                            }
                            return show(ctx.getSource().getSender(), cat);
                        }));
    }

    /**
     * {@code /tf stats detail <key>} — K-5段階3: {@code stats/lore.yml} の {@code trigger:}/{@code limits:}
     * 宣言をプレイヤーがゲーム内で読めるようにするサブコマンド。アイテムlore/チャットhoverは使わない
     * (前者は行数制約、後者はGeyser経由の統合版クライアントで効かないため)。
     */
    private LiteralArgumentBuilder<CommandSourceStack> detailNode() {
        return Commands.literal("detail")
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(Component.text(
                            "使い方: /tf stats detail <キー>", NamedTextColor.RED));
                    return 0;
                })
                .then(Commands.argument("key", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String rem = builder.getRemainingLowerCase();
                            for (String key : loreConfig.displayTable().keySet()) {
                                if (rem.isEmpty() || key.toLowerCase(Locale.ROOT).startsWith(rem)) {
                                    builder.suggest(key);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> detail(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "key"))));
    }

    // show/detail は CommandSourceStack でなく CommandSender を受け取る(パッケージ非公開)。
    // Paper の CommandSourceStack は MockBukkit 環境で組み立てにくく、ここを CommandSender の受け渡しに
    // 留めることで StatsCommandTest が Brigadier のプラミング無しに直接呼び出して検証できる
    // (LoreConfig の parse-helper と同じ「テスト容易性のためのパッケージ非公開化」規約)。
    int show(CommandSender sender, StatsCategory category) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤー専用コマンドです。", NamedTextColor.RED));
            return 0;
        }

        player.sendMessage(Component.text(
                "=== TrinityForge Stats: " + player.getName()
                        + " [" + category.id() + "] ===", NamedTextColor.GOLD));
        player.sendMessage(line("戦闘レベル", Integer.toString(combatService.combatLevelOf(player.getUniqueId()))));

        Map<String, Integer> skills = new TreeMap<>(skillLevelSource.levelsOf(player.getUniqueId()));
        if (skills.isEmpty()) {
            player.sendMessage(line("スキルLv", "(なし / ValhallaMMO未接続)"));
        } else {
            skills.forEach((skill, level) -> player.sendMessage(line("  " + skill, Integer.toString(level))));
        }

        player.sendMessage(Component.text("[合算ステータス]", NamedTextColor.AQUA));
        // 乗算モード: 実際の戦闘/採集パスは全て乗算レイヤ適用後の値を使うため、表示も適用後に揃える。
        sendFiltered(player, combinedStats(player), category);

        if (category == StatsCategory.ALL || category == StatsCategory.ARMOR) {
            AttributeInstance armor = player.getAttribute(Attribute.ARMOR);
            AttributeInstance toughness = player.getAttribute(Attribute.ARMOR_TOUGHNESS);
            player.sendMessage(line("  バニラ防御(armor/toughness)",
                    formatTruncated(armor != null ? armor.getValue() : 0.0)
                            + " / "
                            + formatTruncated(toughness != null ? toughness.getValue() : 0.0)));
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 装備・パーク・アドオンの合算後(乗算レイヤ適用後)ステータス。{@link #show} と
     * {@link #detail(CommandSender, String)} の「現在値」表示が同じ経路を通るようにする
     * ための共通ヘルパー(段階3: 表示経路の重複を作らない)。
     */
    private Map<String, Double> combinedStats(Player player) {
        // 2026-07-29: 実体は PlayerCombatAggregate#combined() へ移した(/tf status のGUIと同じ経路)。
        return aggregator.aggregate(player).combined();
    }

    /**
     * {@code /tf stats detail <key>} 本体。{@code stats/lore.yml} の {@code trigger:}/{@code limits:}
     * 宣言を表示する。未宣言のフィールドは「未宣言」と明示し、それらしい説明を捏造しない
     * (これを破ると仕組み全体の目的に反する、という発注時の明示指示)。
     */
    int detail(CommandSender sender, String rawKey) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤー専用コマンドです。", NamedTextColor.RED));
            return 0;
        }

        Map<String, StatDisplaySpec> table = loreConfig.displayTable();
        StatDisplaySpec spec = table.get(rawKey);
        if (spec == null) {
            spec = lookup(table, StatKeys.canonical(rawKey));
        }
        if (spec == null) {
            sendNotFound(player, rawKey, table);
            return 0;
        }

        player.sendMessage(Component.text(
                "=== " + spec.displayName() + " (" + spec.statKey() + ") ===", NamedTextColor.GOLD));
        player.sendMessage(line("カテゴリ", spec.category().configId()));

        double value = combinedStats(player).getOrDefault(StatKeys.canonical(spec.statKey()), 0.0);
        player.sendMessage(line("現在値", renderForStats(spec, value)));

        player.sendMessage(Component.text("[発動条件]", NamedTextColor.AQUA));
        StatTrigger trigger = spec.trigger();
        if (trigger == null) {
            player.sendMessage(Component.text(
                    "  このステータスの発動条件はまだ登録されていません。", NamedTextColor.DARK_GRAY));
        } else {
            player.sendMessage(line("  発動タイミング", trigger.when().label()));
            player.sendMessage(line("  合算対象", trigger.sources().label()));
            String applies = trigger.appliesTo().stream()
                    .map(StatAppliesTo::label)
                    .collect(Collectors.joining(" / "));
            player.sendMessage(line("  対象", applies));
        }

        player.sendMessage(Component.text("[上限]", NamedTextColor.AQUA));
        StatLimits limits = spec.limits();
        if (limits == null) {
            player.sendMessage(Component.text(
                    "  このステータスの上限はまだ登録されていません。", NamedTextColor.DARK_GRAY));
        } else {
            Map<String, StatBound> bounds = limits.declaredBounds();
            if (bounds.isEmpty() && limits.stacking() == null) {
                player.sendMessage(Component.text(
                        "  上限は宣言されていません(無制限、または未登録)。", NamedTextColor.DARK_GRAY));
            } else {
                for (Map.Entry<String, StatBound> e : bounds.entrySet()) {
                    StatBound bound = e.getValue();
                    String noteRendered = boundValueText(spec, e.getKey(), bound.value());
                    String note = bound.ref() != null ? "(この数字は実装から取得)" : "(実装参照なし)";
                    player.sendMessage(line("  " + boundLabel(e.getKey()), noteRendered + " " + note));
                }
                if (limits.stacking() != null) {
                    player.sendMessage(line("  複数ソースの合成規則", limits.stacking().label()));
                }
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    /** {@code cap}/{@code floor} はステータス自身と同じ単位系(spec.format())で描画、それ以外は専用単位。 */
    private static String boundValueText(StatDisplaySpec spec, String field, double value) {
        return switch (field) {
            case "cap", "floor" -> renderForStats(spec, value);
            case "min-pieces" -> formatTruncated(value) + " 部位";
            case "max-distance" -> formatTruncated(value) + " ブロック";
            case "max-duration-ticks" -> formatTruncated(value) + " tick";
            default -> formatTruncated(value);
        };
    }

    private static String boundLabel(String field) {
        return switch (field) {
            case "cap" -> "上限値";
            case "floor" -> "下限値";
            case "min-pieces" -> "必要防具部位数";
            case "max-distance" -> "最大距離";
            case "max-duration-ticks" -> "最大持続時間";
            default -> field;
        };
    }

    /** 存在しないキー指定時のエラー + 近いキー候補の提示(部分一致、最大5件)。 */
    private static void sendNotFound(Player player, String rawKey, Map<String, StatDisplaySpec> table) {
        player.sendMessage(Component.text("そのようなステータスキーはありません: " + rawKey, NamedTextColor.RED));
        String needle = rawKey.toLowerCase(Locale.ROOT);
        List<String> candidates = table.keySet().stream()
                .filter(k -> k.toLowerCase(Locale.ROOT).contains(needle) || needle.contains(k.toLowerCase(Locale.ROOT)))
                .sorted()
                .limit(5)
                .toList();
        if (!candidates.isEmpty()) {
            player.sendMessage(Component.text("近いキー候補: " + String.join(", ", candidates), NamedTextColor.YELLOW));
        }
    }

    private void sendFiltered(Player player, Map<String, Double> stats, StatsCategory category) {
        Map<String, StatDisplaySpec> table = loreConfig.displayTable();
        boolean any = false;
        for (Map.Entry<String, Double> entry : stats.entrySet().stream()
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, Double> e) -> orderOf(e.getKey(), table))
                        .thenComparing(e -> StatKeys.canonical(e.getKey())))
                .toList()) {
            String key = StatKeys.canonical(entry.getKey());
            double value = entry.getValue();
            if (value == 0.0 || !category.includes(key)) {
                continue;
            }
            StatDisplaySpec spec = lookup(table, key);
            String label = spec != null ? spec.displayName() : key;
            String rendered = spec != null ? renderForStats(spec, value) : formatTruncated(value);
            player.sendMessage(line("  " + label, rendered));
            any = true;
        }
        if (!any) {
            player.sendMessage(Component.text("  (該当カテゴリのステなし)", NamedTextColor.DARK_GRAY));
        }
    }

    private static StatDisplaySpec lookup(Map<String, StatDisplaySpec> table, String canonicalKey) {
        StatDisplaySpec direct = table.get(canonicalKey);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, StatDisplaySpec> e : table.entrySet()) {
            if (StatKeys.canonical(e.getKey()).equals(canonicalKey)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static int orderOf(String key, Map<String, StatDisplaySpec> table) {
        StatDisplaySpec spec = lookup(table, StatKeys.canonical(key));
        return spec != null ? spec.order() : 10_000;
    }

    // 2026-07-29: 実体は StatValueRenderer へ集約した(/tf status のGUIと同じ数値を出すため)。
    // ここに残すのは呼び出しの薄いラッパーだけ。整形規約を変えるときは StatValueRenderer を直す。
    private static String renderForStats(StatDisplaySpec spec, double value) {
        return com.trinityforge.stats.StatValueRenderer.render(spec, value);
    }

    private static String formatTruncated(double value) {
        return com.trinityforge.stats.StatValueRenderer.plain(value);
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

}
