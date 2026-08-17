package com.trinityforge.mining;

import com.trinityforge.active.ActivationDispatcher;
import com.trinityforge.active.ActivationResult;
import com.trinityforge.active.ActiveContext;
import com.trinityforge.active.ActiveSkill;
import com.trinityforge.config.domains.DiggingGimmickConfig;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Objects;
import java.util.Set;

/**
 * {@code haste-active-digging} (SCALE, 2026-08-18 W-59 — the shovel-side counterpart to
 * {@link HasteActiveSkill} that {@code digging.yml} never actually had a working gate placement for).
 * Grants {@link PotionEffectType#HASTE} at the tier-resolved amplifier/duration
 * ({@code stats/digging-gimmick.yml haste-active-digging.tiers}), reading from its OWN
 * {@link DiggingGimmickConfig} — deliberately not sharing a config source, tier table, unlock level, or
 * gate id with {@link HasteActiveSkill}, so this skill's amplifier/duration/cooldown/unlock level can be
 * tuned completely independently of mining's.
 *
 * <p><b>Why a second class instead of widening {@link HasteActiveSkill#targetSkills()} back to include
 * DIGGING</b>: that was the pre-2026-08-18 shape, and it required both trees to share a single
 * {@code feature:haste-active-mining} gate — which is exactly the design that produced the 2026-08-01
 * "シャベルを手に持っていても採掘速度上昇のバフが発動できる" bug (a tree-unaware gate check let a player who
 * only unlocked the mining-side node fire it from a shovel too). Two independently-gated skills, one per
 * tree, is what keeps the fix: {@link ActivationDispatcher} resolves THIS skill's gate
 * ({@link #gateEffectId()}) only against placements in whichever tree the held item's {@code use-skill}
 * names, so a shovel can never accidentally borrow the mining tree's tier.
 *
 * <p><b>Uptime invariant (ユーザー決定 2026-08-18): "3；ただし、持ち替えたらCTに入る"</b> — this skill shares
 * {@link HasteActiveSkill#COOLDOWN_GROUP} via {@link #cooldownGroup()}, so
 * {@link com.trinityforge.active.CooldownManager} tracks one bucket for both skills: firing this one locks
 * out {@link HasteActiveSkill} (and vice versa) for however long the shorter/longer of the two configured
 * cooldowns dictates (whichever skill actually consumed the bucket last — see
 * {@link com.trinityforge.active.CooldownManager} class doc). A player alternating pickaxe/shovel therefore
 * can never get combined uptime exceeding what committing to one tool alone would provide, and switching
 * tools mid-cooldown does not reset or extend anything — it just hits the same shared lock.
 */
public final class DiggingHasteActiveSkill implements ActiveSkill {

    public static final String ID = "haste-active-digging";
    private static final Set<String> TARGET_SKILLS = Set.of("DIGGING");

    private final DiggingGimmickConfig gimmickConfig;

    public DiggingHasteActiveSkill(DiggingGimmickConfig gimmickConfig) {
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "採掘速度上昇(シャベル)";
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
        return HasteActiveSkill.COOLDOWN_GROUP;
    }

    /**
     * {@code tier} is intentionally unused, mirroring {@link HasteActiveSkill#cooldownMillis(int)}: CTは
     * 段階に一切左右されず、常に {@link DiggingGimmickConfig#hasteCooldownTicks()} の1値(全tier共通の
     * 唯一の基準値)。CTを短くする唯一の手段はこのスキル専用のCT短縮ステータス
     * ({@link com.trinityforge.active.ActiveSkillCooldownKeys#forSkill(String)} が {@link #id()} から
     * 導出する — mining側と混同されない独立のキー)。
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
        return ActivationResult.success("採掘強化(シャベル) 発動！ (" + (durationTicks / 20) + "s)");
    }
}
