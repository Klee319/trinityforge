package com.trinityforge.mining;

import com.trinityforge.active.ActivationResult;
import com.trinityforge.active.ActiveContext;
import com.trinityforge.active.ActiveSkill;
import com.trinityforge.config.domains.MiningGimmickConfig;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Objects;
import java.util.Set;

/**
 * {@code haste-active-mining} (SCALE, 2026-07-25 gather-rework-active-framework §3 "移行" — the first
 * {@link ActiveSkill} on the shared framework, replacing the pre-framework
 * {@code HasteActiveMiningListener}'s private {@code Map<UUID, Long>} cooldown + silent-return-on-cooldown
 * with {@code CooldownManager} + {@code FeedbackLayer}). Grants {@link PotionEffectType#HASTE} at the
 * tier-resolved amplifier/duration ({@code stats/mining-gimmick.yml haste-active-mining.tiers}, §1/§6 Q3).
 *
 * <p><b>2026-08-18 第2波(ユーザー確定要件) — CT共有はやめ、「持ち替えで強制終了」へ移した。</b>
 * {@code haste-active-digging} と {@link #cooldownGroup()} を共有していたが、共有CTでは
 * <b>ツルハシで発動してシャベルへ持ち替える</b>ことで効果だけを横流しでき、シャベル側のノードを
 * 解放していないのに掘削が速くなる状態が残っていた。現在は各スキルが独立CTを持ち、
 * {@link #toolBound()} により {@link com.trinityforge.active.ToolBoundEffectListener} が
 * 対象ツールを手放した瞬間に {@link #cancelEffect} で効果を切る(CTは発動時から走ったままなので、
 * 持ち替えは「効果を捨ててCTだけ払う」= 得をしない)。
 *
 * <p><b>2026-08-18 (W-59) — {@link #targetSkills()} was narrowed back down to just {@code "MINING"}.</b>
 * From 2026-07-25 through 2026-08-17 this returned {@code {"MINING", "DIGGING"}}, but {@code digging.yml}
 * never actually had a {@code feature:haste-active-mining} gate placement (only {@code mining.yml} A-1
 * did) — so the "DIGGING" membership was dead: a shovel could never resolve a tier for this gate and the
 * candidate was always skipped in {@link ActivationDispatcher}. The genuinely-reachable shovel-side ability
 * is now the separate {@link com.trinityforge.mining.DiggingHasteActiveSkill} (own id, own gate, own
 * config, own unlock node at {@code digging.yml} A-1), which shares {@link #cooldownGroup()} with this
 * skill so switching tools still cannot exceed either skill's own uptime alone — see
 * {@link ActiveSkill#cooldownGroup()} and {@link ActivationDispatcher}'s 2026-08-18 class-doc note for the
 * full "why not just widen targetSkills() again" reasoning (answer: because that would silently reopen the
 * 2026-08-01 tree-scoped-unlock bug, not because sharing a CT bucket is undesirable).
 */
public final class HasteActiveSkill implements ActiveSkill {

    public static final String ID = "haste-active-mining";
    private static final Set<String> TARGET_SKILLS = Set.of("MINING");

    private final MiningGimmickConfig gimmickConfig;

    public HasteActiveSkill(MiningGimmickConfig gimmickConfig) {
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "採掘速度上昇";
    }

    @Override
    public String gateEffectId() {
        return ID;
    }

    @Override
    public Set<String> targetSkills() {
        return TARGET_SKILLS;
    }

    /**
     * ツールを手放したら維持できない効果(2026-08-18 ユーザー確定要件)。
     * これが {@code false} に戻ると、ツルハシで発動した採掘速度上昇をシャベルへ持ち替えて
     * そのまま使える状態(=シャベル側のノードを解放していないのに掘削が速い)に戻る。
     */
    @Override
    public boolean toolBound() {
        return true;
    }

    @Override
    public int effectDurationTicks(int tier) {
        return gimmickConfig.hasteDurationTicks(tier);
    }

    /**
     * 付与した HASTE を取り消す。<b>剥がすのは「自分が付けたと確認できる効果」だけ</b>:
     * amplifier が発動時の値と一致し、かつ無期限でないこと(ビーコンの採掘速度上昇は amplifier 0/1 で
     * 常時付け直され、無期限効果は管理コマンド由来)。無条件に {@code removePotionEffect} すると
     * W-54(幸運エフェクトを他人が剥がす)と同型の事故になる。
     */
    @Override
    public void cancelEffect(Player player, int tier) {
        PotionEffect current = player.getPotionEffect(PotionEffectType.HASTE);
        if (current == null || current.isInfinite()) {
            return;
        }
        if (current.getAmplifier() != gimmickConfig.hasteAmplifier(tier)) {
            return;
        }
        player.removePotionEffect(PotionEffectType.HASTE);
    }

    /**
     * {@code tier} is intentionally unused (2026-07-25 CT設計一本化: 段階(tier)はCTに一切影響させない —
     * tierが支配するのは{@link #activate}の効果量(amplifier/持続時間)だけ)。CTは常に
     * {@link MiningGimmickConfig#hasteCooldownTicks()} の1値(全tier共通の唯一の基準値)であり、
     * これを短くする唯一の手段は{@link com.trinityforge.active.ActiveSkillCooldownKeys}が解決する
     * per-ActiveSkillのCT短縮ステータス({@link ActivationDispatcher}/{@code ActiveCommand}が適用)。
     */
    @Override
    public long cooldownMillis(int tier) {
        return gimmickConfig.hasteCooldownTicks() * 50L;
    }

    @Override
    public ActivationResult activate(Player player, ActiveContext ctx) {
        int tier = ctx.tier();
        int amplifier = gimmickConfig.hasteAmplifier(tier);
        int durationTicks = gimmickConfig.hasteDurationTicks(tier);
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.HASTE, durationTicks, amplifier, false, true, true));
        return ActivationResult.success("採掘強化 発動！ (" + (durationTicks / 20) + "s)");
    }
}
