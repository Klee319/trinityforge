package com.trinityforge.combat;

/**
 * Configurable clamp bounds for {@link DefenseStats}, sourced from {@code combat/damage.yml}
 * ({@code defense.min-rate}/{@code max-rate}/{@code min-flat}/{@code max-flat}). Replaces the old
 * hard-coded {@code [0,1]} rate clamp and {@code [0,∞)} flat floor so an operator can deliberately
 * allow NEGATIVE defensive values (負クランプ): a negative 被ダメージ軽減% amplifies incoming damage,
 * and a large-enough over-mitigation (max-rate &gt; 1) can drive final damage below zero — which the
 * pipeline then turns into HEALING (攻撃0未満は敵が回復) once {@code min-component-damage} is allowed
 * to go negative.
 *
 * <p>The defaults {@code (minRate=0, maxRate=1, minFlat=0, maxFlat=+large)} reproduce the previous
 * hard-coded behaviour exactly, so an untouched install is unchanged (回帰なし).
 *
 * @param minRate lower bound for the three rate fields (防御率% / 耐性% / 被ダメージ軽減%)
 * @param maxRate upper bound for the three rate fields (set &gt; 1 to permit over-mitigation → heal)
 * @param minFlat lower bound for the single flat field (守備力); negative = armor amplifies. 防具強度 uses
 *                {@code minRate} as its lower bound and 1 as its structural upper bound.
 * @param maxFlat upper bound for the single flat field (守備力)
 */
public record DefenseClamp(double minRate, double maxRate, double minFlat, double maxFlat) {

    /** The legacy hard-coded behaviour: rates in {@code [0,1]}, flats floored at 0 (no upper bound). */
    public static final DefenseClamp LEGACY = new DefenseClamp(0.0, 1.0, 0.0, Double.MAX_VALUE);

    public DefenseClamp {
        // A misconfigured inverted range (min > max) would make clamp() collapse everything to a single
        // point; guard by swapping so the wider bound always wins rather than silently zeroing stats.
        if (minRate > maxRate) {
            double tmp = minRate;
            minRate = maxRate;
            maxRate = tmp;
        }
        if (minFlat > maxFlat) {
            double tmp = minFlat;
            minFlat = maxFlat;
            maxFlat = tmp;
        }
    }
}
