package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Validates {@link DefenseStats}: the compact constructor now only finite-guards (NaN/±Inf → 0); the
 * domain clamp (rate/flat bounds, incl. NEGATIVE bounds for 負クランプ) moved to
 * {@link DefenseStats#clampedTo(DefenseClamp)}. {@link DefenseStats#cappedMitigation(double)} now
 * also caps 防御率% (defenseRate), not just 耐性%/被ダメージ軽減% (B3 fix, #3: an attacker with
 * penetration=0 got no benefit from defenseRate being "penetrable", so leaving it uncapped still
 * allowed additive stacking to reach 1.0 = complete immunity for them).
 */
class DefenseStatsValidationTest {

    // --- compact constructor: finite-guard ONLY (no domain clamp anymore) ---

    @Test
    void constructorKeepsOutOfRangeRatesUnclamped() {
        // The constructor no longer clamps to [0,1]; values survive for the downstream config clamp.
        DefenseStats stats = new DefenseStats(1.5, 2.0, 3.0, 0, 0);
        assertEquals(1.5, stats.defenseRate());
        assertEquals(2.0, stats.resistance());
        assertEquals(3.0, stats.damageReduction());
    }

    @Test
    void constructorKeepsNegativeValuesUnclamped() {
        // Negative defensive values (負クランプ) must survive construction/combine to reach the pipeline.
        DefenseStats stats = new DefenseStats(-0.2, -0.1, -0.5, -5.0, -2.0);
        assertEquals(-0.2, stats.defenseRate());
        assertEquals(-0.1, stats.resistance());
        assertEquals(-0.5, stats.damageReduction());
        assertEquals(-5.0, stats.flatDefense());
        assertEquals(-2.0, stats.armorStrength());
    }

    @Test
    void nanFieldsCollapseToZero() {
        DefenseStats stats = new DefenseStats(Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN);
        assertEquals(0.0, stats.defenseRate());
        assertEquals(0.0, stats.resistance());
        assertEquals(0.0, stats.damageReduction());
        assertEquals(0.0, stats.flatDefense());
        assertEquals(0.0, stats.armorStrength());
    }

    @Test
    void infiniteFieldsCollapseToZero() {
        DefenseStats stats = new DefenseStats(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
        assertEquals(0.0, stats.defenseRate());
        assertEquals(0.0, stats.resistance());
        assertEquals(0.0, stats.damageReduction());
        assertEquals(0.0, stats.flatDefense());
        assertEquals(0.0, stats.armorStrength());
    }

    // --- clampedTo(DefenseClamp): the relocated domain clamp ---

    @Test
    void clampedToLegacyReproducesOldZeroOneClamp() {
        DefenseStats stats = new DefenseStats(1.5, 2.0, 3.0, -5.0, -2.0)
                .clampedTo(DefenseClamp.LEGACY);
        assertEquals(1.0, stats.defenseRate());
        assertEquals(1.0, stats.resistance());
        assertEquals(1.0, stats.damageReduction());
        assertEquals(0.0, stats.flatDefense());     // legacy floor at 0
        assertEquals(0.0, stats.armorStrength());
    }

    @Test
    void clampedToLegacyFloorsNegativeRatesAtZero() {
        DefenseStats stats = new DefenseStats(-0.2, -0.1, -0.5, 0, 0)
                .clampedTo(DefenseClamp.LEGACY);
        assertEquals(0.0, stats.defenseRate());
        assertEquals(0.0, stats.resistance());
        assertEquals(0.0, stats.damageReduction());
    }

    @Test
    void clampedToWithNegativeBoundsLetsValuesGoNegative() {
        // Operator opts into amplification/heal: min-rate -1, max-rate 2, min-flat -10, max-flat 10.
        DefenseClamp bounds = new DefenseClamp(-1.0, 2.0, -10.0, 10.0);
        DefenseStats stats = new DefenseStats(-0.5, 1.8, 3.0, -8.0, 20.0).clampedTo(bounds);
        assertEquals(-0.5, stats.defenseRate());     // within [-1,2]
        assertEquals(1.8, stats.resistance());        // within [-1,2]
        assertEquals(2.0, stats.damageReduction());   // clamped to max-rate 2 (over-mitigation → heal)
        assertEquals(-8.0, stats.flatDefense());      // within [-10,10]
        assertEquals(1.0, stats.armorStrength());     // 防具強度=会心軽減率: clamped to [0,1] (flat bounds do not apply)
    }

    // --- cappedCritReduction: the 防具強度(会心軽減率%) balance ceiling (defense.max-crit-reduction) ---

    @Test
    void cappedCritReductionLimitsArmorStrengthOnly() {
        DefenseStats stats = new DefenseStats(0.5, 0.5, 0.5, 3.0, 0.8).cappedCritReduction(0.6);
        assertEquals(0.6, stats.armorStrength());    // capped to max-crit-reduction 0.6
        assertEquals(0.5, stats.defenseRate());      // rates untouched
        assertEquals(0.5, stats.resistance());
        assertEquals(0.5, stats.damageReduction());
        assertEquals(3.0, stats.flatDefense());      // flat untouched
    }

    @Test
    void cappedCritReductionAtOneCapsOnlyThePositiveCeiling() {
        // Default cap 1.0 caps positive overflow but preserves authored negative curse values.
        assertEquals(1.0, new DefenseStats(0, 0, 0, 0, 1.5).cappedCritReduction(1.0).armorStrength());
        assertEquals(-0.3, new DefenseStats(0, 0, 0, 0, -0.3).cappedCritReduction(1.0).armorStrength());
    }

    @Test
    void defenseClampSwapsInvertedRanges() {
        // A misconfigured inverted range must not collapse everything to a point.
        DefenseClamp bounds = new DefenseClamp(1.0, 0.0, 10.0, -10.0);
        assertEquals(0.0, bounds.minRate());
        assertEquals(1.0, bounds.maxRate());
        assertEquals(-10.0, bounds.minFlat());
        assertEquals(10.0, bounds.maxFlat());
    }

    // --- cappedMitigation: now also caps defenseRate (B3 fix, #3) ---

    @Test
    void cappedMitigationLimitsResistanceReductionAndDefenseRate() {
        DefenseStats stats = new DefenseStats(0.95, 1.0, 1.0, 3.0, 2.0).cappedMitigation(0.9);
        assertEquals(0.9, stats.resistance());       // capped
        assertEquals(0.9, stats.damageReduction());  // capped
        assertEquals(0.9, stats.defenseRate());      // now capped too (penetration=0 attackers get no exemption)
        assertEquals(3.0, stats.flatDefense());      // flat untouched
        assertEquals(2.0, stats.armorStrength());
    }

    @Test
    void cappedMitigationBelowOneLeavesFullDefenseRateNoLongerFullImmunity() {
        // A defender that additively stacked defenseRate to 1.0 (= complete immunity against a
        // penetration=0 attacker, the exact B3 failure this closes) is now capped like the other rates.
        DefenseStats stats = new DefenseStats(1.0, 0, 0, 0, 0).cappedMitigation(0.9);
        assertEquals(0.9, stats.defenseRate());
    }

    @Test
    void cappedMitigationAtOneIsNoOp() {
        DefenseStats stats = new DefenseStats(1.0, 0.8, 0.7, 0, 0).cappedMitigation(1.0);
        assertEquals(1.0, stats.defenseRate());
        assertEquals(0.8, stats.resistance());
        assertEquals(0.7, stats.damageReduction());
    }

    @Test
    void noneConstantStaysAllZero() {
        assertEquals(0.0, DefenseStats.NONE.defenseRate());
        assertEquals(0.0, DefenseStats.NONE.resistance());
        assertEquals(0.0, DefenseStats.NONE.damageReduction());
        assertEquals(0.0, DefenseStats.NONE.flatDefense());
        assertEquals(0.0, DefenseStats.NONE.armorStrength());
    }
}
