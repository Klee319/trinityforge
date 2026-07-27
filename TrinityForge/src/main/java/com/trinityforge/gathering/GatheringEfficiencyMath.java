package com.trinityforge.gathering;

/**
 * Pure arithmetic behind {@link GatheringEfficiencyEnchantApplier}'s value-to-enchant-level conversion
 * (2026-07-25 採集効率エンチャント連動方式 — the vanilla-Attribute approach (mining-efficiency /
 * mining-speed-bonus, {@code MINING_EFFICIENCY} / {@code BLOCK_BREAK_SPEED}) was withdrawn because those
 * attributes do not exist on Bedrock and are not reflected in Geyser's break-time calculation
 * (GeyserMC/Geyser#6266), so Bedrock players saw the tool speed flicker between vanilla and boosted.
 * Efficiency-the-enchant IS honoured by Geyser, so this resolver instead floors the aggregated
 * {@code gathering-efficiency} stat value into a real vanilla Efficiency enchant level).
 *
 * <p>Bukkit-free and side-effect-free so the conversion rule is unit-testable without a server.
 */
public final class GatheringEfficiencyMath {

    /**
     * 内部ハード上限(2026-07-26 ユーザー決定: 設定上限を撤廃しても、これだけは残す)。
     *
     * <p>理由: バニラのエンチャントレベルはNBT上 {@code short} で扱われるため、際限のない値は
     * オーバーフローや不合理な挙動(表示崩れ・比較演算の異常等)を招く。{@code max-level: 0}
     * (無制限)を設定した場合でも、このハード上限だけは超えない。255 は short の範囲に十分収まり、
     * かつ実運用であり得る値を大きく超える安全マージンとして選んだ定数。
     */
    private static final int HARD_CAP = 255;

    private GatheringEfficiencyMath() {
    }

    /**
     * Converts an aggregated {@code gathering-efficiency} stat total into the Efficiency enchant level
     * TF should apply. Mirrors {@code ItemAssembler}'s existing {@code tool-enchant-*} precedent
     * (ITEM_ECONOMY_SPEC 5.2b javadoc): the fractional accumulation itself IS the threshold mechanic, so
     * the value is floored rather than rounded.
     *
     * <p>2026-07-26 ユーザー決定: 設定上限({@code maxLevel})を撤廃可能にした。{@code maxLevel <= 0}
     * は「無制限」を意味し、{@link #HARD_CAP} だけでクランプする。正の値は従来どおりその値を上限として
     * 使う(ただし {@link #HARD_CAP} を超える設定値は {@link #HARD_CAP} に丸める)。
     *
     * @param totalValue the aggregated stat total (may be fractional, negative, NaN, or infinite)
     * @param maxLevel   the configured ceiling ({@code stats/gathering-efficiency.yml}'s
     *                   {@code max-level}); {@code <= 0} means unlimited (subject only to
     *                   {@link #HARD_CAP})
     * @return {@code 0} when {@code totalValue} is non-finite or {@code <= 0} (never removes an
     *     already-present enchant level a player earned some other way — the caller is responsible for
     *     applying this as a delta on top of, not a replacement of, the item's existing level), otherwise
     *     {@code floor(totalValue)} clamped to {@code [0, effectiveMax]} where {@code effectiveMax} is
     *     {@code min(maxLevel, HARD_CAP)} when {@code maxLevel > 0}, else {@link #HARD_CAP}.
     */
    public static int resolveLevel(double totalValue, int maxLevel) {
        if (!Double.isFinite(totalValue) || totalValue <= 0.0) {
            return 0;
        }
        int floored = (int) Math.floor(totalValue);
        int effectiveMax = maxLevel > 0 ? Math.min(maxLevel, HARD_CAP) : HARD_CAP;
        return Math.min(floored, effectiveMax);
    }
}
