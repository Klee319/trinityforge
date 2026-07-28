package com.trinityforge.active;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * アクティブスキルのクールダウン残り時間を、進行中だけアクションバーへ出し続ける
 * (2026-07-28 ユーザー要望「アクティブスキルのクールタイム中に残り何秒かの表示が欲しい」)。
 *
 * <p>これまで残り時間が見えるのは「CT中に発動しようとした瞬間」だけ
 * ({@link ActivationDispatcher} → {@link FeedbackLayer#onCooldown})で、
 * <strong>いつ撃てるようになるのかは押してみるまで分からなかった</strong>。
 *
 * <p>表示条件はトリガー条件と同じ絞り込みにしている — メインハンドの {@code use-skill} が
 * そのアクティブスキルの対象で、かつ解放済みのときだけ。関係ない道具を持っている間まで
 * アクションバーを占有すると、一括伐採等の他のフィードバックを潰してしまうため。
 *
 * <p>負荷対策として、{@link CooldownManager#hasRecord} で「そもそも一度も使っていない」
 * プレイヤーを先に弾いてから {@link PlayerStatAggregator#aggregate} を呼ぶ
 * (CT短縮ステータスの合算はここでの唯一の重い処理)。
 */
public final class ActiveCooldownDisplay implements Runnable {

    /** 0.5秒ごと。アクションバーの表示は数秒残るので、これより細かくしても見た目は変わらない。 */
    private static final long PERIOD_TICKS = 10L;

    private final Plugin plugin;
    private final ActiveSkillRegistry registry;
    private final DedicatedEffectsConfig dedicatedEffects;
    private final CooldownManager cooldowns;
    private final FeedbackLayer feedback;
    private final PlayerStatAggregator aggregator;

    public ActiveCooldownDisplay(Plugin plugin, ActiveSkillRegistry registry,
                                 DedicatedEffectsConfig dedicatedEffects, CooldownManager cooldowns,
                                 FeedbackLayer feedback, PlayerStatAggregator aggregator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this, PERIOD_TICKS, PERIOD_TICKS);
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            showFor(player, now);
        }
    }

    private void showFor(Player player, long now) {
        String useSkill = ActivationDispatcher.mainHandUseSkill(player.getInventory().getItemInMainHand());
        if (useSkill == null) {
            return;
        }
        for (ActiveSkill skill : registry.forTargetSkill(useSkill)) {
            if (!cooldowns.hasRecord(player.getUniqueId(), skill.id())) {
                continue; // 一度も使っていない = CTは走っていない(重い集計を避ける)
            }
            OptionalDouble tier = dedicatedEffects.valueMax(player, skill.gateEffectId());
            if (tier.isEmpty()) {
                continue;
            }
            // 発動側(ActivationDispatcher)と同じ短縮後の長さで計算しないと、表示と実際の解禁時刻がずれる。
            double reduction = aggregator.aggregate(player)
                    .totalOf(ActiveSkillCooldownKeys.forSkill(skill.id()));
            long cooldownMillis = CooldownManager.applyReduction(
                    skill.cooldownMillis((int) tier.getAsDouble()), reduction);
            long remaining = cooldowns.remainingMillis(player.getUniqueId(), skill.id(), cooldownMillis, now);
            if (remaining > 0L) {
                // アクションバーは1行しか出せないので、最初に見つかった1件だけ表示する。
                feedback.cooldownTicking(player, skill.displayName(), remaining);
                return;
            }
        }
    }
}
