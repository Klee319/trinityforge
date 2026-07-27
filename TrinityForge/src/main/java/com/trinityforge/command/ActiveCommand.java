package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.active.ActivationResult;
import com.trinityforge.active.ActiveContext;
import com.trinityforge.active.ActiveSkill;
import com.trinityforge.active.ActiveSkillCooldownKeys;
import com.trinityforge.active.ActiveSkillRegistry;
import com.trinityforge.active.CooldownManager;
import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.OptionalDouble;
import java.util.Objects;

/**
 * {@code /tf active <id>} — DEBUG-ONLY alternate activation path (2026-07-25
 * gather-rework-active-framework §3 component 4 / §6 Q2, as overridden by the orchestrating brief). The
 * real player-facing trigger is sneak+right-click with the matching {@code use-skill} item
 * ({@code ActivationDispatcher}); this command exists purely so a tester/admin can trigger an
 * {@link ActiveSkill} without holding the exact item, or diagnose why one won't fire (unlocked? on
 * cooldown?). It is gated {@code trinityforge.admin} at the registration site
 * ({@code TrinityForge#registerCommands}) and deliberately NOT mentioned in the {@code /tf} root help text
 * or advertised to non-admin tab-completion — brief: "ヘルプやタブ補完などプレイヤー向け導線には出さないこと".
 *
 * <p>Bypasses the item-match check (there is no "held item" requirement here) but still enforces the same
 * gate/cooldown rules as the real trigger, so it is a faithful debug surface rather than a bypass of the
 * unlock system.
 */
public final class ActiveCommand {

    private final ActiveSkillRegistry registry;
    private final DedicatedEffectsConfig dedicatedEffects;
    private final CooldownManager cooldowns;
    private final FeedbackLayer feedback;
    private final PlayerStatAggregator aggregator;

    public ActiveCommand(ActiveSkillRegistry registry, DedicatedEffectsConfig dedicatedEffects,
                          CooldownManager cooldowns, FeedbackLayer feedback,
                          PlayerStatAggregator aggregator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("active")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String rem = builder.getRemainingLowerCase();
                            registry.all().stream()
                                    .map(ActiveSkill::id)
                                    .filter(id -> rem.isEmpty() || id.toLowerCase(java.util.Locale.ROOT).startsWith(rem))
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> activate(ctx.getSource(), StringArgumentType.getString(ctx, "id"))));
    }

    private int activate(CommandSourceStack source, String id) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤー専用コマンドです。", NamedTextColor.RED));
            return 0;
        }
        var skill = registry.get(id);
        if (skill.isEmpty()) {
            player.sendMessage(Component.text("未知のアクティブID: " + id, NamedTextColor.RED));
            return 0;
        }
        ActiveSkill active = skill.get();
        OptionalDouble tierOpt = dedicatedEffects.valueMax(player, active.gateEffectId());
        if (tierOpt.isEmpty()) {
            player.sendMessage(Component.text("未解放です: " + id, NamedTextColor.RED));
            return 0;
        }
        int tier = (int) tierOpt.getAsDouble();
        // 2026-07-25 CT設計一本化 §2: 実トリガー(ActivationDispatcher)と同じper-skill短縮キーを適用する
        // ——このデバッグコマンドが「未解放/CT中の再現」を偽らないため。
        double skillCooldownReduction = aggregator.aggregate(player)
                .totalOf(ActiveSkillCooldownKeys.forSkill(active.id()));
        long cooldownMillis = CooldownManager.applyReduction(active.cooldownMillis(tier), skillCooldownReduction);
        long now = System.currentTimeMillis();
        if (!cooldowns.tryConsume(player.getUniqueId(), active.id(), cooldownMillis, now)) {
            long remainingMillis = cooldowns.remainingMillis(player.getUniqueId(), active.id(), cooldownMillis, now);
            feedback.onCooldown(player, Math.max(1, Math.ceilDiv(remainingMillis, 1000L)));
            return 0;
        }
        ActivationResult result = active.activate(player, new ActiveContext(tier, player.getInventory().getItemInMainHand()));
        if (result.success()) {
            feedback.success(player, result.feedbackMessage());
        } else {
            feedback.failure(player, result.feedbackMessage());
        }
        return Command.SINGLE_SUCCESS;
    }
}
