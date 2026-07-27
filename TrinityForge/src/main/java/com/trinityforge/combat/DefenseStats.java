package com.trinityforge.combat;

import java.util.Objects;

/**
 * Defender-side inputs for one damage component (COMBAT_SYSTEM_SPEC 2.1 / 3.2-3.4).
 * Percent values are fractions: 0.20 == 20%.
 *
 * @param defenseRate     防御率% [0,1], penetrable (step 4)
 * @param resistance      該当耐性% [0,1] for this type, NOT penetrable (step 5)
 * @param damageReduction 被ダメージ軽減% [0,1], applied as x(1-reduction) (step 6)
 * @param flatDefense     守備力 (flat), subtracted at step 2 (前段、%軽減より先). NOT refunded by
 *                        固定ダメージ any more — 固定ダメージ(step 8)は無条件の純加算であり、flat が
 *                        削った分の還付という意味論は廃止された。Per-component field, but common per
 *                        LD-13 for player-derived values (same in both types); a mob PDC profile may
 *                        still author a per-type value (COMBAT §6 / LD-13 M4). By default floored at 0
 *                        by {@link #clampedTo}, but an operator may allow a negative bound
 *                        ({@code defense.min-flat &lt; 0}) so armor <em>amplifies</em> incoming damage
 *                        (base -= negative) on purpose; the step-2 zero-clamp only floors the RESULT at
 *                        0, so this amplification is not blocked.
 * @param armorStrength   防具強度 = 会心軽減率% (type-independent). Applied at step 3, NOT step 2:
 *                        it multiplies the crit bonus by {@code (1 - armorStrength)} so a crit deals
 *                        {@code base × (1 + critDamage × (1 - armorStrength))}. Only the crit surplus is
 *                        reduced (通常ダメージには不干渉). Its lower bound follows {@code defense.min-rate}
 *                        and its upper bound is capped by {@link #cappedCritReduction}; negative values
 *                        amplify incoming crits. Additive across sources via {@link #combine}.
 */
public record DefenseStats(
        double defenseRate,
        double resistance,
        double damageReduction,
        double flatDefense,
        double armorStrength
) {
    /**
     * Only guards finiteness (NaN/±Inf → 0) on every construction path; the domain clamp (rate/flat
     * bounds) is NO LONGER applied here. It moved to {@link #clampedTo(DefenseClamp)}, invoked at the
     * single {@code SymmetricCombatService.component()} choke where every defender source (item, perk,
     * addon, vanilla mirror, potion, mob PDC) has already been {@code combine}d — so a NEGATIVE
     * defensive value (負クランプ) can survive addition here and be bounded once, by config, downstream.
     * Callers that need the legacy hard {@code [0,1]}/{@code [0,∞)} bounds use
     * {@code clampedTo(DefenseClamp.LEGACY)}.
     */
    public DefenseStats {
        defenseRate = finiteOrZero(defenseRate);
        resistance = finiteOrZero(resistance);
        damageReduction = finiteOrZero(damageReduction);
        flatDefense = finiteOrZero(flatDefense);
        armorStrength = finiteOrZero(armorStrength);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * A copy with the config-driven domain clamp applied: the three rate fields (防御率% / 耐性% /
     * 被ダメージ軽減%) bounded to {@code [minRate, maxRate]} and the single flat field (守備力) to
     * {@code [minFlat, maxFlat]}. 防具強度 is a crit-reduction RATE (not flat) so it is clamped to a
     * {@code [minRate,1]} here — its balance ceiling is applied separately by
     * {@link #cappedCritReduction}. With {@link DefenseClamp#LEGACY} the rate/flat fields reproduce the
     * old compact-constructor behaviour ({@code [0,1]} rates, {@code [0,∞)} flat). A configured
     * {@code maxRate > 1} lets 被ダメージ軽減% exceed full mitigation so the pipeline's {@code x(1 -
     * reduction)} step goes negative → the component can deal negative (healing) damage; a negative
     * {@code minFlat}/{@code minRate} lets defensive gear amplify incoming damage instead of reducing it.
     */
    public DefenseStats clampedTo(DefenseClamp bounds) {
        Objects.requireNonNull(bounds, "bounds");
        return new DefenseStats(
                clamp(defenseRate, bounds.minRate(), bounds.maxRate()),
                clamp(resistance, bounds.minRate(), bounds.maxRate()),
                clamp(damageReduction, bounds.minRate(), bounds.maxRate()),
                clamp(flatDefense, bounds.minFlat(), bounds.maxFlat()),
                clamp(armorStrength, bounds.minRate(), 1.0));
    }

    /**
     * Field-wise additive combine (LD-8 γ = additive composition). Used to merge the vanilla
     * armor/toughness mirror (防御率% + 防具強度) with the TF-only item/skill defense (耐性% +
     * 守備力 + 被ダメージ軽減%) into one profile; each source leaves the other's fields at 0, so the
     * sum simply unions them. The result is NOT clamped here — the config-driven domain clamp is applied
     * once downstream via {@link #clampedTo(DefenseClamp)} at the pipeline choke.
     *
     * <p><strong>Saturation caveat (decide before wiring the LD-8 γ armor-skill baseline):</strong>
     * {@code armorStrength}(会心軽減率%) is now summed across up to four non-zero sources (item bridge +
     * vanilla mirror + perk + addon), so additive stacking of overlapping rates already happens for it —
     * it is bounded downstream by {@code defense.min-rate} in {@link #clampedTo} and the {@code max-crit-reduction}
     * ceiling in {@link #cappedCritReduction}, not by this combine. {@code defenseRate} is still only
     * filled by the vanilla mirror today, but once the armor-skill baseline adds an <em>overlapping</em>
     * rate (e.g. also {@code defenseRate}), additive stacking would clamp to 1.0 = full immunity (zero
     * damage when penetration is 0). The [0,1] clamp is a safety floor, NOT the intended balance ceiling:
     * pick additive-vs-multiplicative stacking for overlapping mitigation rates at that point rather than
     * relying on the silent clamp.
     */
    public DefenseStats combine(DefenseStats other) {
        Objects.requireNonNull(other, "other");
        return new DefenseStats(
                defenseRate + other.defenseRate,
                resistance + other.resistance,
                damageReduction + other.damageReduction,
                flatDefense + other.flatDefense,
                armorStrength + other.armorStrength);
    }

    /**
     * A copy with all three mitigation rates (防御率% / 耐性% / 被ダメージ軽減%) capped at
     * {@code maxRate}. The constructor's [0,1] clamp is only a safety floor; this is the tunable
     * balance ceiling ({@code combat/damage.yml defense.max-mitigation-rate}) that stops additive
     * armor/potion stacking from reaching 1.0 = total immunity (B3). 防御率% is now capped here too
     * (previously exempted as "penetrable, so harmless") because an attacker with {@code penetration
     * == 0} (common for mobs and many players) gets no benefit from that penetrability — additive
     * stacking to {@code defenseRate == 1.0} still constituted complete, unconditional immunity for
     * them, contradicting the "貫通可能だから安全" rationale.
     */
    public DefenseStats cappedMitigation(double maxRate) {
        double cap = clamp01(finiteOrZero(maxRate));
        return new DefenseStats(
                Math.min(defenseRate, cap),
                Math.min(resistance, cap),
                Math.min(damageReduction, cap),
                flatDefense,
                armorStrength);
    }

    /**
     * A copy with 防具強度(会心軽減率%) capped at {@code maxCritReduction} ({@code combat/damage.yml
     * defense.max-crit-reduction}). The default {@code 1.0} imposes no ceiling below full nullification
     * of the crit bonus (キャップ無し). Negative values have already been bounded by
     * {@code defense.min-rate} and remain negative here. Setting the cap below 1.0 guarantees a crit keeps at
     * least {@code (1 - maxCritReduction)} of its bonus, so no amount of additive 防具強度 stacking can
     * fully cancel crits. Only 防具強度 is touched; the mitigation rates are bounded by
     * {@link #cappedMitigation}.
     */
    public DefenseStats cappedCritReduction(double maxCritReduction) {
        double cap = clamp01(finiteOrZero(maxCritReduction));
        return new DefenseStats(
                defenseRate,
                resistance,
                damageReduction,
                flatDefense,
                Math.min(armorStrength, cap));
    }

    /** Vanilla mob baseline: no mitigation, only level (COMBAT_SYSTEM_SPEC 6). */
    public static final DefenseStats NONE = new DefenseStats(0, 0, 0, 0, 0);
}
