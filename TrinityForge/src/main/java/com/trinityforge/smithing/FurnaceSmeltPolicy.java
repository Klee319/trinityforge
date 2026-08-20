package com.trinityforge.smithing;

/**
 * Pure helpers shared by {@code FurnaceSmeltListener} (smithing.yml A-1/A-2/A-3「精錬速度」/
 * B-1/B-2/B-3「精錬ボーナス」): cook-time reduction and the extra-drop percent roll, both scaled down
 * by {@link com.trinityforge.config.domains.SmithingGimmickConfig#autoModeMultiplier} when the
 * furnace was last fed by a hopper (automated) rather than a player's own hand. Bukkit-free so both
 * are unit-testable with fixed inputs, same style as {@link com.trinityforge.mining.MiningGimmickPolicy}.
 */
public final class FurnaceSmeltPolicy {

    private FurnaceSmeltPolicy() {
    }

    /**
     * Resolves the effective reduction/bonus percent for the current smelt, applying
     * {@code autoModeMultiplier} when {@code automated}. Non-finite/negative {@code rawPercent} yields
     * 0; the result is never negative.
     */
    public static double effectivePercent(double rawPercent, boolean automated, double autoModeMultiplier) {
        if (!Double.isFinite(rawPercent) || rawPercent <= 0.0) {
            return 0.0;
        }
        double multiplier = automated ? clamp01(autoModeMultiplier) : 1.0;
        return rawPercent * multiplier;
    }

    /**
     * 精錬にかかる tick 数。<b>{@code speedPercent} は「精錬速度が何%増えるか」</b>で、
     * {@code 新しい時間 = 基準 ÷ (1 + speedPercent/100)}。
     * 100 なら 2 倍速(=時間は半分)、170 なら 2.7 倍速。最低 1 tick でクランプする。
     *
     * <p><b>2026-08-19 (W-150) に意味を変えた。</b>それまでは同じ数値を「調理時間を何%短縮するか」と
     * 解釈していた({@code 基準 × (1 - percent/100)})。この解釈だと
     * <ul>
     *   <li>出荷 tier3 の {@code percent: 100} が<b>短縮100% = 1 tick</b>になり、
     *       原木1スタックが約3.2秒で焼き終わる(実サーバ報告の症状そのもの)、</li>
     *   <li>スキルツリーが謳う「精錬速度+100%」(=2倍速)を<b>表現できない</b>
     *       (短縮は100%が上限で、その100%が無限倍速を意味してしまう)</li>
     * </ul>
     * という2点が同時に起きていた。ユーザー判断で「+X% = 速度がX%増える」に統一した
     * (「170%上昇なら2.7倍」という自然な読みに合わせる)。100 を超える値も意味を持つ。
     */
    public static int cookTimeWithSpeedBonus(int baseCookTime, double speedPercent) {
        if (baseCookTime <= 0) {
            return baseCookTime;
        }
        double percent = Double.isFinite(speedPercent) ? Math.max(0.0, speedPercent) : 0.0;
        int adjusted = (int) Math.round(baseCookTime / (1.0 + percent / 100.0));
        return Math.max(1, adjusted);
    }

    private static double clamp01(double raw) {
        if (!Double.isFinite(raw) || raw < 0.0) {
            return 0.0;
        }
        return Math.min(raw, 1.0);
    }
}
