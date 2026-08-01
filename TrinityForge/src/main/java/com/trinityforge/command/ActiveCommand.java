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
 *
 * <h2>ここだけツリー横断のままなのは意図的（2026-08-01）</h2>
 * <p>{@code ActivationDispatcher} は 2026-08-01 の実サーバ報告
 * (「シャベルを持っていても採掘速度上昇が発動する」)を受けて、ゲートを<b>持ち替えたツールの
 * {@code use-skill} と同じスキルツリーの配置だけ</b>に絞って解決するようになった
 * ({@code DedicatedEffectsConfig#valueMax(player, effectId, skill)})。
 * <b>このコマンドは意図的にその絞り込みを行わず、2引数版のツリー横断解決を使い続ける。</b>
 * 「持ち物」という概念自体がここには無い(=絞り込むべき {@code useSkill} が存在しない)ためで、
 * 絞り込みの代わりにできることは「どれか1本のツリーを勝手に選ぶ」しかなく、それは
 * <b>デバッグ用途としてむしろ嘘になる</b>(mining だけ解放したプレイヤーに対し、コマンドは
 * 「解放されている」と正しく答えるべきで、シャベルツリー基準で「未解放」と答えてはいけない)。
 * したがってこのコマンドの「未解放です」表示は<b>どのツリーでも一切解放していないこと</b>を意味し、
 * 実トリガーが撃てるかどうかとは一致しない — <b>手に持ったツールでは発動しないのにここでは通る</b>
 * ケースが正常に存在する。それを切り分けるのが管理者の仕事なので、
 * ここを「実トリガーと完全一致」に寄せる修正を入れてはいけない
 * (絞り込みたくなったら、まず {@link ActiveSkill#targetSkills()} のどれを選ぶのかを説明できること)。
 * ※ゲート以外(CT短縮キー/CT長/フィードバック)は実トリガーと同一のまま。
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
        // 意図的にツリー横断(2引数版)。ここには「持ったツールの use-skill」が無いので絞り込む軸が
        // 存在しない。実トリガー(ActivationDispatcher)はツリー限定なので、両者の結果は一致しない
        // ことがある——それが正しい。詳細はクラスjavadocの「ここだけツリー横断のままなのは意図的」節。
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
