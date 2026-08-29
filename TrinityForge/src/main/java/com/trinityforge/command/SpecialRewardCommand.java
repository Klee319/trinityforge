package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.ParticleSeedDelivery;
import com.trinityforge.progression.SpecialRewardService;
import com.trinityforge.skilltree.runtime.PerkAttributeApplier;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /tf reward <grant|revoke|list> …} — 特殊報酬(称号/パーティクル/パーティクルシード)を
 * コマンドから直接付与・剥奪・確認する (2026-07-27)。
 *
 * <p>これまで特殊報酬の付与経路はアチーブメント達成と図鑑ティア解放だけで、運営が手で配る手段が
 * 無かった(イベント配布・不具合の補填・テストのいずれもできなかった)。付与先は
 * {@link PlayerData#grantSpecialReward} — アチーブメント/図鑑ティアが使うのと<b>同じ「直接付与」枠</b>
 * なので、スキルツリー由来の保有({@code reward:<id>} perk)とは独立に積み上がる。
 *
 * <p>剥奪できるのは直接付与された分だけ。perk 由来の保有はスキルツリー側が真実なので、
 * {@code revoke} しても保有判定は true のまま残る(その旨をコマンドが明示的に警告する)。
 *
 * <p>対象はオンラインプレイヤーのみ。特殊報酬はプレイヤーPDCに載るため、オフラインの相手へは
 * 書き込めない(サーバをまたいだ保留キューを持ち込むほどの機能ではないという判断)。
 */
public final class SpecialRewardCommand {

    private final SpecialRewardsConfig config;
    private final SpecialRewardService service;
    /** null 可。ATTRIBUTE系の再適用は特殊報酬には無いが、称号表示の張り直しに合わせて残してある。 */
    private final PerkAttributeApplier perkAttributeApplier;
    /** null 可(fail-soft)。パーティクルシードIDを付与したとき、シード素材と使い方を渡す。 */
    private final ParticleSeedDelivery particleSeedDelivery;

    public SpecialRewardCommand(SpecialRewardsConfig config, SpecialRewardService service,
                                 PerkAttributeApplier perkAttributeApplier) {
        this(config, service, perkAttributeApplier, null);
    }

    public SpecialRewardCommand(SpecialRewardsConfig config, SpecialRewardService service,
                                 PerkAttributeApplier perkAttributeApplier,
                                 ParticleSeedDelivery particleSeedDelivery) {
        this.config = Objects.requireNonNull(config, "config");
        this.service = Objects.requireNonNull(service, "service");
        this.perkAttributeApplier = perkAttributeApplier;
        this.particleSeedDelivery = particleSeedDelivery;
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("reward")
                .then(Commands.literal("grant")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestPlayers(builder))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestRewardIds(builder))
                                        .executes(ctx -> grant(ctx.getSource().getSender(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "id"))))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestPlayers(builder))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestRewardIds(builder))
                                        .executes(ctx -> revoke(ctx.getSource().getSender(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "id"))))))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource().getSender(), null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestPlayers(builder))
                                .executes(ctx -> list(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player")))));
    }

    private int grant(CommandSender sender, String playerName, String id) {
        Player target = resolve(sender, playerName);
        if (target == null) {
            return 0;
        }
        if (!isDefined(id)) {
            sendUnknownId(sender, id);
            return 0;
        }
        PlayerData data = PlayerData.of(target);
        if (data.unlockedSpecialRewards().contains(id)) {
            sender.sendMessage(Component.text(target.getName() + " は既に " + id + " を保有しています。",
                    NamedTextColor.YELLOW));
            return Command.SINGLE_SUCCESS;
        }
        data.grantSpecialReward(id);
        if (particleSeedDelivery != null) {
            // パーティクルシードだけは「解放しただけでは手元に何も無い」ので、
            // アチーブ/図鑑と同じくシード素材と使い方をここでも渡す(2026-08-21)。
            particleSeedDelivery.deliver(target, id);
        }
        if (perkAttributeApplier != null) {
            perkAttributeApplier.apply(target);
        }
        sender.sendMessage(Component.text("付与: " + id + " → " + target.getName(), NamedTextColor.GREEN));
        target.sendMessage(Component.text("特殊報酬を獲得しました: ", NamedTextColor.GOLD)
                .append(Component.text(describe(id), NamedTextColor.YELLOW)));
        return Command.SINGLE_SUCCESS;
    }

    private int revoke(CommandSender sender, String playerName, String id) {
        Player target = resolve(sender, playerName);
        if (target == null) {
            return 0;
        }
        boolean removed = PlayerData.of(target).revokeSpecialReward(id);
        if (!removed) {
            sender.sendMessage(Component.text(target.getName() + " は " + id
                    + " を直接付与では保有していません。", NamedTextColor.YELLOW));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(Component.text("剥奪: " + id + " ← " + target.getName(), NamedTextColor.GREEN));
        if (service.isUnlocked(target, id)) {
            // スキルツリーの reward: perk 側でまだ保有している。ここを黙って成功扱いにすると
            // 「消したのに残っている」と見えるので、理由まで含めて明示する。
            sender.sendMessage(Component.text("※ ただし " + id
                    + " はスキルツリーのperk由来でまだ保有中です(perkを剥がすまで有効)。",
                    NamedTextColor.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandSender sender, String playerName) {
        Player target;
        if (playerName == null || playerName.isBlank()) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(Component.text("コンソールからは /tf reward list <player> を使ってください。",
                        NamedTextColor.RED));
                return 0;
            }
            target = self;
        } else {
            target = resolve(sender, playerName);
            if (target == null) {
                return 0;
            }
        }
        List<String> direct = PlayerData.of(target).unlockedSpecialRewards();
        sender.sendMessage(Component.text("=== " + target.getName() + " の特殊報酬 ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("直接付与: " + (direct.isEmpty() ? "なし" : String.join(", ", direct)),
                NamedTextColor.WHITE));
        Set<String> viaPerk = new LinkedHashSet<>();
        for (String id : allIds()) {
            if (!direct.contains(id) && service.isUnlocked(target, id)) {
                viaPerk.add(id);
            }
        }
        sender.sendMessage(Component.text("perk由来: " + (viaPerk.isEmpty() ? "なし" : String.join(", ", viaPerk)),
                NamedTextColor.GRAY));
        // パーティクルシードだけは装備画面(/tf settings)に出ないので、保有していても
        // 使い方に辿り着けない。ここで素材名まで出す(2026-08-21 の修正前に解放済みだった
        // プレイヤーには解放時の案内が届いていないため、事後の唯一の確認手段になる)。
        Set<String> heldSeeds = new LinkedHashSet<>();
        for (String id : config.particleSeeds().keySet()) {
            if (direct.contains(id) || viaPerk.contains(id)) {
                heldSeeds.add(id);
            }
        }
        for (String id : heldSeeds) {
            SpecialRewardsConfig.ParticleSeed seed = config.particleSeeds().get(id);
            sender.sendMessage(Component.text("  シード " + id + ": " + seed.seedItem()
                    + " と 道具/武器 の2つだけを作業台に置く", NamedTextColor.LIGHT_PURPLE));
        }
        return Command.SINGLE_SUCCESS;
    }

    private Player resolve(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(Component.text("オンラインではありません: " + playerName, NamedTextColor.RED));
        }
        return target;
    }

    private void sendUnknownId(CommandSender sender, String id) {
        sender.sendMessage(Component.text("未定義の特殊報酬ID: " + id
                + " (progression/special-rewards.yml の titles/particles/particle-seeds)",
                NamedTextColor.RED));
    }

    private boolean isDefined(String id) {
        return config.titles().containsKey(id)
                || config.particles().containsKey(id)
                || config.particleSeeds().containsKey(id);
    }

    private String describe(String id) {
        if (config.titles().containsKey(id)) {
            return id + " (称号)";
        }
        if (config.particles().containsKey(id)) {
            return id + " (パーティクル)";
        }
        if (config.particleSeeds().containsKey(id)) {
            return id + " (パーティクルシード)";
        }
        return id;
    }

    private Set<String> allIds() {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(config.titles().keySet());
        ids.addAll(config.particles().keySet());
        ids.addAll(config.particleSeeds().keySet());
        return ids;
    }

    private CompletableFuture<Suggestions> suggestRewardIds(SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String id : allIds()) {
            if (remaining.isEmpty() || id.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestPlayers(SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (remaining.isEmpty() || player.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(player.getName());
            }
        }
        return builder.buildFuture();
    }
}
