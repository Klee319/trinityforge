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
 * <p><b>Uptime invariant (ユーザー確定要件 2026-08-18 第2波): 「共有ではなく該当のツールから持ち変えると
 * 効果が強制終了する方針」</b> — 初版はこのスキルと {@link HasteActiveSkill} で CT バケツを共有して
 * 「持ち替えて連発できない」だけを担保していたが、<b>効果そのものは持ち替えても残る</b>ので、
 * ツルハシで発動してシャベルへ持ち替えれば「シャベル側のノードを解放していないのに掘削が速い」状態が
 * 作れた(CT共有では効果の横流しは止められない)。現在は CT は各スキル独立で、
 * {@link #toolBound()} により {@link com.trinityforge.active.ToolBoundEffectListener} が
 * <b>メインハンドが {@link #targetSkills()} 外になった瞬間</b>に {@link #cancelEffect} を呼ぶ。
 * CT は発動時から走ったままなので、持ち替えは「効果を捨てて CT だけ払う」= 常に損。
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

    /**
     * ツールを手放したら維持できない効果(2026-08-18 ユーザー確定要件)。
     * {@code cooldownGroup()} の共有(初版)はこの方式へ置き換えたので上書きしない
     * ── CT は {@link #id()} 単位で mining 側と完全に独立している。
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
     * 付与した HASTE を取り消す。剥がすのは「amplifier が発動時の値と一致し、かつ無期限でない」
     * 効果だけ(ビーコン/管理コマンド由来を剥がさないための照合。{@link HasteActiveSkill#cancelEffect}
     * と同じ規約)。
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
