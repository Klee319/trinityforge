package com.trinityforge.stats;

/**
 * The quality-dependent roll distribution for a per-item random stat (ITEM_ECONOMY_SPEC 5.1 roll layer,
 * 段2). Given an item's quality, it maps a standard-normal draw {@code Z ∈ N(0,1)} to a "reach" fraction
 * {@code R ∈ [0,1]} of the stat's {@code {min, max}} range via a split-normal (two-piece Gaussian) centered
 * on a quality-proportional mode: the upward half ({@code Z ≥ 0}) spreads by {@code rollSpreadUp}, the
 * downward half by {@code rollSpreadDown}, then the value is clamped to {@code [0, 1]}. This mirrors the
 * quality-LEVEL split-normal (spread-up/spread-down in
 * {@link com.trinityforge.config.domains.QualityConfig}); here it shapes how a stat rolls WITHIN its range
 * once the quality level is fixed.
 *
 * <ul>
 *   <li>{@code qNorm = quality / maxQuality} ∈ [0,1] — the raw quality fraction</li>
 *   <li>{@code mode = rollCenterInset + qNorm·(1 - 2·rollCenterInset)} — the roll center, compressed into
 *       {@code [inset, 1-inset]} so even quality 0 / max are held off the hard {@code [0,1]} edges. With
 *       {@code inset = 0} the mode is exactly {@code qNorm} (min at quality 0, max at top quality); a
 *       positive inset keeps both tails on-range so extreme qualities still form a bell rather than an
 *       edge spike (裾広がりの山形)</li>
 *   <li>{@code σ = Z ≥ 0 ? rollSpreadUp : rollSpreadDown} — the split spread (上振れ/下振れ を別々に)</li>
 *   <li>{@code R = clamp(mode + Z·σ, 0, 1)} — the reach fraction; higher quality shifts the whole bell
 *       toward {@code max}, and the tails still let a low-quality item occasionally reach {@code max}</li>
 * </ul>
 *
 * @param maxQuality     the effective highest quality level (0 collapses qNorm to 0)
 * @param rollSpreadUp   {@code >= 0}; σ of the roll's upper half (toward max) in reach-fraction units
 * @param rollSpreadDown {@code >= 0}; σ of the roll's lower half (toward min) in reach-fraction units
 * @param rollCenterInset {@code [0, 0.5)}; how far the roll center is held off the {@code [0,1]} edges at
 *                        extreme quality (0 = mode reaches the edges; larger = flatter, more centered bell)
 */
public record QualityRollModel(int maxQuality, double rollSpreadUp, double rollSpreadDown,
                               double rollCenterInset) {

    public QualityRollModel {
        if (maxQuality < 0) {
            throw new IllegalArgumentException("maxQuality must be >= 0");
        }
        if (!Double.isFinite(rollSpreadUp) || rollSpreadUp < 0) {
            throw new IllegalArgumentException("rollSpreadUp must be a finite value >= 0");
        }
        if (!Double.isFinite(rollSpreadDown) || rollSpreadDown < 0) {
            throw new IllegalArgumentException("rollSpreadDown must be a finite value >= 0");
        }
        if (!Double.isFinite(rollCenterInset) || rollCenterInset < 0 || rollCenterInset >= 0.5) {
            throw new IllegalArgumentException("rollCenterInset must be a finite value in [0, 0.5)");
        }
    }

    /** {@code quality} clamped to {@code [0, maxQuality]}. */
    public int clampQuality(int quality) {
        return Math.max(0, Math.min(maxQuality, quality));
    }

    /** The normalized quality fraction {@code clampQuality(quality) / maxQuality} (0 when maxQuality is 0). */
    public double qualityFraction(int quality) {
        return maxQuality == 0 ? 0.0 : (double) clampQuality(quality) / maxQuality;
    }

    /**
     * The roll center (mode) for the given quality: {@code rollCenterInset + qNorm·(1 - 2·rollCenterInset)},
     * i.e. the quality fraction compressed into {@code [inset, 1-inset]}. With {@code inset = 0} this is just
     * {@code qNorm}; a positive inset holds the center off the {@code [0,1]} edges so an extreme-quality roll
     * keeps tails on both sides (a bell instead of an edge spike).
     */
    public double modeAt(int quality) {
        double qNorm = qualityFraction(quality);
        return rollCenterInset + qNorm * (1.0 - 2.0 * rollCenterInset);
    }

    /**
     * The reach fraction {@code R ∈ [0, 1]} for a standard-normal draw {@code standardNormal} at the given
     * quality: {@code clamp(mode + Z·σ, 0, 1)}, where {@code mode} is {@link #modeAt} and {@code σ} is
     * {@code rollSpreadUp} when {@code Z ≥ 0} and {@code rollSpreadDown} otherwise (split-normal). Used as
     * {@code range.valueAt(reach)} to produce the rolled stat value. A σ of 0 on the chosen side pins the
     * reach at the (clamped) mode.
     */
    public double reach(int quality, double standardNormal) {
        double sigma = standardNormal >= 0.0 ? rollSpreadUp : rollSpreadDown;
        double r = modeAt(quality) + standardNormal * sigma;
        return Math.max(0.0, Math.min(1.0, r));
    }

    /**
     * A new model with the crafter's stage-2 perk deltas applied: up σ widened by {@code upBonus} (≥0),
     * down σ narrowed by {@code downReduction} (≥0, floored at 0), and inset REDUCED by {@code
     * insetDelta} (≥0, floored at 0) — a positive inset perk pushes a skilled crafter's high-quality
     * rolls closer to the {@code max} edge rather than holding them off it — clamped to [0, 0.4999] so it
     * stays inside the record's [0,0.5) invariant.
     */
    public QualityRollModel withCraftMods(double upBonus, double downReduction, double insetDelta) {
        double up = rollSpreadUp + Math.max(0.0, upBonus);
        double down = Math.max(0.0, rollSpreadDown - Math.max(0.0, downReduction));
        double inset = Math.max(0.0, Math.min(0.4999, rollCenterInset - Math.max(0.0, insetDelta)));
        return new QualityRollModel(maxQuality, up, down, inset);
    }

    /** {@link #withCraftMods(double, double, double)} sourced from a {@link CraftRollMods} bundle. */
    public QualityRollModel withCraftMods(CraftRollMods mods) {
        return withCraftMods(mods.rollUpBonus(), mods.rollDownReduction(), mods.rollInsetDelta());
    }
}
