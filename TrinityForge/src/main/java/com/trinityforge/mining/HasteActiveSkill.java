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
    /**
     * {@link ActiveSkill#cooldownGroup()} shared with {@link DiggingHasteActiveSkill}
     * (2026-08-18 W-59): alternating pickaxe/shovel consumes the same CT bucket, so combined uptime from
     * the two skills together never exceeds what either skill provides alone.
     */
    public static final String COOLDOWN_GROUP = "haste-active";
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

    @Override
    public String cooldownGroup() {
        return COOLDOWN_GROUP;
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
