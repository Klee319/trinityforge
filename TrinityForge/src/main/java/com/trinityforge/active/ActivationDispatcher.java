package com.trinityforge.active;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.pdc.ItemData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * The one player-facing activation entry point (2026-07-25 gather-rework-active-framework §3 component 4,
 * as overridden by the orchestrating brief §6 Q2 — the design doc's {@code /tf active}+GUI trigger was
 * REJECTED in favor of this): <b>sneak + right-click, while the main-hand item's {@code use-skill} tag
 * is a member of an {@link ActiveSkill#targetSkills()}</b>. No GUI exists in this wave. {@code /tf active <id>}
 * (see {@code ActiveCommand}) stays as a debug-only alternate path, not advertised in
 * help/tab-completion.
 *
 * <p>Item-target matching is by the item's {@code use-skill} PDC tag ({@link ItemData#useSkill()}) ONLY —
 * never inferred from {@code Material} (matches the existing {@code PerkBuffResolver#matchesMainHandSkill}
 * convention, which this class deliberately mirrors rather than re-derives differently).
 *
 * <p>Event-cancellation contract (brief, non-negotiable): {@link PlayerInteractEvent#setCancelled(true)}
 * fires ONLY when an activation actually succeeds (or a skill-internal {@link ActivationResult#failure}
 * fires — that is still "this event was consumed by an activation attempt"). A refusal at the CT/gate/
 * item-mismatch stage — cooldown still running, tier not unlocked, held item untagged or tagged for a
 * different skill — never cancels the event, so normal block-place/item-use behavior is never disturbed by
 * a player who is not actively trying to trigger an ability.
 *
 * <p><b>スキルCT短縮(2026-07-25 CT設計一本化 §2)</b>: {@code cooldown-reduction} は
 * {@code CombatListener.startItemCooldown} がアイテムCTにだけ乗算する既存キーで、このディスパッチャは
 * 一切参照しない。アクティブスキルのCTは {@link ActiveSkillCooldownKeys#forSkill(String)} が
 * {@link ActiveSkill#id()} から導出する<b>スキル単位の</b>キー(例: {@code haste-active-mining} なら
 * {@code haste-active-mining-cooldown-reduction})だけを読む — 旧設計はあらゆるアクティブスキルで1本の
 * グローバルキーを共有しており、ある採掘スキルツリーのノードで得たCT短縮が無関係の別アクティブスキルにも
 * 波及するという不具合があった(詳細は {@link ActiveSkillCooldownKeys} のクラスコメント参照)。適用箇所は
 * {@link ActiveSkill#cooldownMillis(int)} の呼び出し直後、{@link CooldownManager#tryConsume}/
 * {@link CooldownManager#remainingMillis} に渡す前——両者に同じ短縮後の値を渡さないと、consumeとremaining
 * 表示の基準がずれる(短縮前後で別の長さを比べてしまう)ため、ここで一度だけ計算して両呼び出しに使い回す。
 * 値の合算は {@link PlayerStatAggregator} の既存経路({@code aggregate(player).totalOf(...)}) に乗せ、
 * クランプは {@link CooldownManager#applyReduction} で {@code cooldown-reduction} と同じ流儀(揃えた上下限)
 * にする。
 *
 * <p><b>2026-08-18(W-59) CTバケツのキーは {@link ActiveSkill#id()} ではなく {@link ActiveSkill#cooldownGroup()}</b>:
 * {@link CooldownManager#tryConsume}/{@link CooldownManager#remainingMillis} へ渡す第2引数はこのキー。
 * 既定は {@code id()} と同じなので大半のスキルは今までどおりだが、{@code haste-active-mining} と
 * {@code haste-active-digging} のように独立した2つの {@link ActiveSkill} が同じ定数を返すよう
 * オーバーライドすると、CTバケツを共有しながら解放レベル・倍率・持続・CT長はそれぞれ独立に保てる
 * (ツールを持ち替えて連発しても合計アップタイムが増えない — 実サーバ報告「持ち替えでCT無視できる」対策)。
 * {@code CT短縮ステータス}(直上の段落)は引き続き {@link ActiveSkill#id()} 単位(スキル固有の短縮)であり、
 * これと混同しないこと。
 */
public final class ActivationDispatcher implements Listener {

    private final ActiveSkillRegistry registry;
    private final DedicatedEffectsConfig dedicatedEffects;
    private final CooldownManager cooldowns;
    private final FeedbackLayer feedback;
    private final PlayerStatAggregator aggregator;
    private final ActiveEffectSessions sessions;

    public ActivationDispatcher(ActiveSkillRegistry registry, DedicatedEffectsConfig dedicatedEffects,
                                 CooldownManager cooldowns, FeedbackLayer feedback,
                                 PlayerStatAggregator aggregator) {
        this(registry, dedicatedEffects, cooldowns, feedback, aggregator, new ActiveEffectSessions());
    }

    /**
     * {@link ActiveEffectSessions} を明示注入する版(2026-08-18)。{@link ToolBoundEffectListener} と
     * <b>同じインスタンス</b>を渡さないと、持ち替え検知が「セッションが開いていない」と見て何もしない。
     * 引数省略版は自前のセッション台帳を作るので、テスト以外では使わないこと。
     */
    public ActivationDispatcher(ActiveSkillRegistry registry, DedicatedEffectsConfig dedicatedEffects,
                                 CooldownManager cooldowns, FeedbackLayer feedback,
                                 PlayerStatAggregator aggregator, ActiveEffectSessions sessions) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /**
     * <b>{@code ignoreCancelled} を付けてはいけない(2026-08-03)。</b>{@link PlayerInteractEvent} は
     * クリックしたブロックが {@code null}(= {@code RIGHT_CLICK_AIR})のとき、誰もキャンセルしていなくても
     * 生成時点から {@code isCancelled() == true} になるため、{@code ignoreCancelled = true} を付けると
     * 空クリックが一切配送されない。アクティブスキルの正式トリガーは「スニーク+右クリック」なので、
     * これが付いている間は<b>ブロックに向けて撃たない限りアクティブが一切発動しない</b>。
     * 理由の詳細は {@code com.trinityforge.listeners.GachaListener#onInteract} の javadoc。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        String useSkill = mainHandUseSkill(mainHand);
        if (useSkill == null) {
            return; // untagged item: not an activation attempt at all, never cancel.
        }
        List<ActiveSkill> candidates = registry.forTargetSkill(useSkill);
        if (candidates.isEmpty()) {
            return; // no active belongs to this skill-tree: nothing to do, never cancel.
        }

        long now = System.currentTimeMillis();
        for (ActiveSkill skill : candidates) {
            // ゲートは「持っているツールのスキルツリー」に限定して解決する(2026-08-01 実サーバ報告
            // 「シャベルを手に持っていても採掘速度上昇のバフが発動できる」の修正)。
            // haste-active-mining は mining.yml A-1 と digging.yml A-1 の両方が置くゲートなので、
            // ツリーを問わず最大値を取ると **ツルハシ側しか解放していないプレイヤーがシャベルでも
            // 発動できて**しまい、逆も同様だった。CTは従来どおり ActiveSkill#id() 単位で共有する
            // (ツリーごとに別CTにはしない)。
            OptionalDouble tierOpt = dedicatedEffects.valueMax(player, skill.gateEffectId(), useSkill);
            if (tierOpt.isEmpty()) {
                continue; // this candidate isn't unlocked for the player: try the next one, never cancel.
            }
            int tier = (int) tierOpt.getAsDouble();
            double skillCooldownReduction = aggregator.aggregate(player)
                    .totalOf(ActiveSkillCooldownKeys.forSkill(skill.id()));
            long cooldownMillis = CooldownManager.applyReduction(skill.cooldownMillis(tier), skillCooldownReduction);
            String cooldownKey = skill.cooldownGroup();
            if (!cooldowns.tryConsume(player.getUniqueId(), cooldownKey, cooldownMillis, now)) {
                long remainingMillis = cooldowns.remainingMillis(player.getUniqueId(), cooldownKey, cooldownMillis, now);
                feedback.onCooldown(player, Math.max(1, Math.ceilDiv(remainingMillis, 1000L)));
                return; // on cooldown: an activation attempt was made, but never cancel (brief, non-negotiable).
            }

            ActivationResult result = skill.activate(player, new ActiveContext(tier, mainHand));
            event.setCancelled(true);
            if (result.success()) {
                // 2026-08-18: ツール束縛の効果はセッションを開く。ToolBoundEffectListener が
                // 「メインハンドが targetSkills() から外れた瞬間」に cancelEffect を呼ぶための土台で、
                // これが無いと持ち替えても効果が残り、別ツールへ横流しできてしまう。
                if (skill.toolBound()) {
                    sessions.open(player.getUniqueId(), skill.id(), tier,
                            now + skill.effectDurationTicks(tier) * 50L);
                }
                feedback.success(player, result.feedbackMessage());
            } else {
                feedback.failure(player, result.feedbackMessage());
            }
            return;
        }
        // Every candidate for this skill is either unresolved (tier not unlocked): not an activation
        // attempt the player could have succeeded at, so stay silent and never cancel.
    }

    /** Drops every cooldown entry so the map never grows unbounded over server uptime. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.clear(event.getPlayer().getUniqueId());
    }

    /**
     * The held item's {@code use-skill} tag, upper-cased, or {@code null} when absent/blank
     * (mirrors {@code PerkBuffResolver#matchesMainHandSkill}: never falls back to Material inference).
     *
     * <p>Public so {@code com.trinityforge.gathering.GatheringEfficiencyEnchantApplier} can reuse the
     * exact same "which skill does the mainhand item belong to" resolution (2026-07-25 採集効率
     * エンチャント連動方式) instead of re-deriving it — a bare vanilla tool with no TF PDC stamp resolves
     * to {@code null} here (it has never been assigned a {@code use-skill}), so it gets no bonus; this is
     * intentional, not a bug, and there is deliberately no Material-based fallback.
     */
    public static String mainHandUseSkill(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        String tagged = ItemData.of(stack.getItemMeta()).useSkill().orElse("").trim();
        return tagged.isBlank() ? null : tagged.toUpperCase(Locale.ROOT);
    }
}
