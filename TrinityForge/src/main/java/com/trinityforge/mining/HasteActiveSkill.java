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
 * <p>2026-07-25 regression fix: the gate ({@code feature:haste-active-mining}) is placed by BOTH
 * {@code mining.yml} A-1 (pickaxe tree) AND {@code digging.yml} A-1 (shovel tree) — the pre-framework
 * {@code HasteActiveMiningListener} triggered off either tool type via material inspection
 * ({@code isPickaxeOrShovel}). {@link #targetSkills()} therefore returns both {@code "MINING"} and
 * {@code "DIGGING"} so a shovel tagged {@code use-skill: DIGGING} keeps triggering this exactly as a
 * pickaxe tagged {@code use-skill: MINING} does, with a single shared cooldown (see {@link ActiveSkill}
 * class doc) — a player cannot extend uptime by switching tools mid-cooldown.
 */
public final class HasteActiveSkill implements ActiveSkill {

    public static final String ID = "haste-active-mining";
    private static final Set<String> TARGET_SKILLS = Set.of("MINING", "DIGGING");

    private final MiningGimmickConfig gimmickConfig;

    public HasteActiveSkill(MiningGimmickConfig gimmickConfig) {
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
    }

    @Override
    public String id() {
        return ID;
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
