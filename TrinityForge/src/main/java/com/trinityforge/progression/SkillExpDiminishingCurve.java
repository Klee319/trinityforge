package com.trinityforge.progression;

import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.progression.catalog.FormulaParser;
import com.trinityforge.progression.core.SkillId;

import java.util.Objects;
import java.util.Set;

/**
 * {@link ExpDiminishingCurve} の TrinityForge 実装(2026-07-26 EXP調整タスク3)。
 *
 * <p>{@code stats/skill-exp.yml} の {@code level-diminishing.*} を読み、採取スキル
 * (MINING/FARMING/WOODCUTTING/DIGGING)と戦闘スキル(武器2種/防具2種/ARS_MAGIC)へ
 * それぞれ独立に(片方だけでも)適用できる。どちらも既定でOFFなので、config変更が無い限り
 * このクラスは常に{@code 1.0}を返す = 実サーバの挙動は変わらない。
 *
 * <p>式は既存の{@code exp_level_curve}と同じ{@link FormulaParser}文法({@code %level%}を含む
 * 四則演算・べき乗)を再利用する。評価結果はそのままEXP倍率として扱われ、
 * {@code level-diminishing.floor}(既定0.2)と1.0の間へクランプされる — 「後半どれだけ薄まっても
 * 完全に0にはしない」「壊れた式で急に増える」の両方を防ぐ安全弁。
 */
public final class SkillExpDiminishingCurve implements ExpDiminishingCurve {

    /** タスク1と同じ4採取スキル。 */
    private static final Set<String> GATHERING_SKILLS = Set.of(
            SkillId.MINING, SkillId.FARMING, SkillId.WOODCUTTING, SkillId.DIGGING);
    /** 戦闘系6スキル(軽・重武器、弓術、軽・重防具、Ars魔法)。 */
    private static final Set<String> COMBAT_SKILLS = Set.of(
            SkillId.HEAVY_WEAPONS, SkillId.LIGHT_WEAPONS, SkillId.ARCHERY,
            SkillId.HEAVY_ARMOR, SkillId.LIGHT_ARMOR, SkillId.ARS_MAGIC);

    private final SkillExpConfig config;

    public SkillExpDiminishingCurve(SkillExpConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public double multiplierFor(String skillId, int currentLevel) {
        if (skillId == null || currentLevel <= 0) {
            return 1.0;
        }
        boolean gathering = GATHERING_SKILLS.contains(skillId);
        boolean combat = !gathering && COMBAT_SKILLS.contains(skillId);
        if (!gathering && !combat) {
            // 採取/戦闘のどちらにも属さないスキル(SMITHING/ALCHEMY/ENCHANTING/FISHING/ARS_SMITHING/POWER)
            // はタスク3の対象外 — ブリーフの「採取・戦闘の両方に効くようにし」の通り、それ以外は触らない。
            return 1.0;
        }
        if (gathering && !config.gatheringExpDiminishingEnabled()) {
            return 1.0;
        }
        if (combat && !config.combatExpDiminishingEnabled()) {
            return 1.0;
        }
        double raw;
        try {
            raw = FormulaParser.evaluate(config.expDiminishingFormula(), currentLevel);
        } catch (RuntimeException ex) {
            // 壊れた式は逓減を諦めて1.0(=現行挙動)へフォールセーフする。EXP付与バッチ全体を巻き込んで
            // 失敗させない方針は NativeExperienceDispatcher#drain の broad catch と同じ考え方。
            return 1.0;
        }
        if (!Double.isFinite(raw)) {
            return 1.0;
        }
        double floor = Math.max(0.0, Math.min(1.0, config.expDiminishingFloor()));
        return Math.max(floor, Math.min(1.0, raw));
    }
}
