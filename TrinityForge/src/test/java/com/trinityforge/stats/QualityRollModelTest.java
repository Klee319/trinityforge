package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-tests {@link QualityRollModel}, the split-normal roll distribution (段2):
 * {@code R = clamp(mode + Z·σ, 0, 1)} with {@code σ = Z ≥ 0 ? rollSpreadUp : rollSpreadDown} and the mode
 * {@code rollCenterInset + qNorm·(1 - 2·rollCenterInset)} (qNorm = quality / maxQuality). With inset 0 the
 * mode is exactly qNorm: low quality centers the roll near min, top quality near max, tails allow the
 * opposite end. A positive inset holds the mode off the [0,1] edges so extreme qualities still form a bell.
 */
class QualityRollModelTest {

    private static final QualityRollModel MODEL = new QualityRollModel(9, 0.5, 0.5, 0.0);

    @Test
    void reachAtMinQualityCentersOnMinAndSpreadsUpward() {
        // quality 0: mode 0 (inset 0). Z>=0 uses σ=0.5.
        assertEquals(0.0, MODEL.reach(0, 0.0), 1e-12);   // mode
        assertEquals(0.5, MODEL.reach(0, 1.0), 1e-12);   // +1σ
        assertEquals(1.0, MODEL.reach(0, 2.0), 1e-12);   // +2σ clamps at 1
        assertEquals(0.0, MODEL.reach(0, -1.0), 1e-12);  // below mode clamps at 0
    }

    @Test
    void reachAtMaxQualityCentersOnMaxAndSpreadsDownward() {
        // quality 9: mode 1 (inset 0). Z<0 uses σ=0.5.
        assertEquals(1.0, MODEL.reach(9, 0.0), 1e-12);   // mode
        assertEquals(0.5, MODEL.reach(9, -1.0), 1e-12);  // -1σ
        assertEquals(0.0, MODEL.reach(9, -2.0), 1e-12);  // -2σ clamps at 0
        assertEquals(1.0, MODEL.reach(9, 1.0), 1e-12);   // above max clamps at 1
    }

    @Test
    void upAndDownSpreadsAreSelectedByTheSignOfTheDraw() {
        // qNorm = 0.5 at quality 5 of max 10; asymmetric spreads prove the split.
        QualityRollModel split = new QualityRollModel(10, 0.4, 0.2, 0.0);
        assertEquals(0.9, split.reach(5, 1.0), 1e-12);   // Z>=0 -> up spread 0.4
        assertEquals(0.3, split.reach(5, -1.0), 1e-12);  // Z<0  -> down spread 0.2
        assertEquals(0.5, split.reach(5, 0.0), 1e-12);   // mode
    }

    @Test
    void higherQualityShiftsTheModeTowardMax() {
        double low = MODEL.reach(0, 0.0);
        double high = MODEL.reach(9, 0.0);
        assertTrue(high > low, "the roll mode must rise with quality");
        assertEquals(0.0, low, 1e-12);
        assertEquals(1.0, high, 1e-12);
    }

    @Test
    void reachIsAlwaysClampedIntoZeroToOne() {
        assertEquals(1.0, MODEL.reach(5, 100.0), 1e-12);
        assertEquals(0.0, MODEL.reach(5, -100.0), 1e-12);
    }

    @Test
    void zeroSpreadPinsThatSideAtTheMode() {
        QualityRollModel pinned = new QualityRollModel(10, 0.0, 0.0, 0.0);
        assertEquals(0.5, pinned.reach(5, 3.0), 1e-12);   // up σ0 -> stays at mode
        assertEquals(0.5, pinned.reach(5, -3.0), 1e-12);  // down σ0 -> stays at mode
    }

    // ---- roll-center-inset: hold the mode off the [0,1] edges so extreme qualities still form a bell ----

    @Test
    void modeWithZeroInsetEqualsTheQualityFraction() {
        QualityRollModel m = new QualityRollModel(10, 0.15, 0.15, 0.0);
        assertEquals(0.0, m.modeAt(0), 1e-12);
        assertEquals(0.5, m.modeAt(5), 1e-12);
        assertEquals(1.0, m.modeAt(10), 1e-12);
    }

    @Test
    void modeWithInsetIsCompressedIntoTheInsetBand() {
        QualityRollModel m = new QualityRollModel(10, 0.15, 0.15, 0.1);
        assertEquals(0.1, m.modeAt(0), 1e-12);   // quality 0 -> inset, not the min edge
        assertEquals(0.5, m.modeAt(5), 1e-12);   // mid quality unchanged
        assertEquals(0.9, m.modeAt(10), 1e-12);  // top quality -> 1 - inset, not the max edge
    }

    @Test
    void insetKeepsTailsOnBothSidesAtExtremeQuality() {
        // With inset the quality-0 mode is 0.1, so a downward draw still lands above the floor before
        // clamping and an upward draw rises — a bell, not an all-at-min spike.
        QualityRollModel m = new QualityRollModel(10, 0.15, 0.15, 0.1);
        assertEquals(0.1, m.reach(0, 0.0), 1e-12);              // peak sits at the inset, off the edge
        assertEquals(0.25, m.reach(0, 1.0), 1e-12);            // +1σ tail toward max
        assertEquals(0.0, m.reach(0, -1.0), 1e-9);            // -1σ (0.1-0.15) clamps to 0: left tail exists
        assertTrue(m.reach(0, -0.5) > 0.0, "a small downward draw stays above the floor (a real tail)");
    }

    @Test
    void qualityIsClampedIntoRange() {
        assertEquals(0, MODEL.clampQuality(-4));
        assertEquals(9, MODEL.clampQuality(100));
        assertEquals(4, MODEL.clampQuality(4));
    }

    @Test
    void qualityFractionIsZeroWhenMaxQualityIsZero() {
        QualityRollModel flat = new QualityRollModel(0, 0.5, 0.5, 0.0);
        assertEquals(0.0, flat.qualityFraction(0), 0.0);
        // qNorm pinned to 0: any quality centers the roll at min.
        assertEquals(0.0, flat.reach(50, 0.0), 1e-12);
    }

    // ---- withCraftMods: 段2 鍛冶ロールパークが焼き込む per-crafter モデル調整 ----

    @Test
    void withCraftModsWidensTheUpSpread() {
        QualityRollModel base = new QualityRollModel(10, 0.2, 0.2, 0.1);
        QualityRollModel adjusted = base.withCraftMods(0.3, 0.0, 0.0);
        assertEquals(0.5, adjusted.rollSpreadUp(), 1e-12);
        assertEquals(0.2, adjusted.rollSpreadDown(), 1e-12);
        assertEquals(0.1, adjusted.rollCenterInset(), 1e-12);
    }

    @Test
    void withCraftModsNarrowsTheDownSpreadFlooredAtZero() {
        QualityRollModel base = new QualityRollModel(10, 0.2, 0.2, 0.1);
        assertEquals(0.05, base.withCraftMods(0.0, 0.15, 0.0).rollSpreadDown(), 1e-12);
        // A reduction larger than the base spread floors at 0 rather than going negative.
        assertEquals(0.0, base.withCraftMods(0.0, 999.0, 0.0).rollSpreadDown(), 1e-12);
    }

    @Test
    void withCraftModsReducesTheInsetTowardMax() {
        // A positive inset perk REDUCES rollCenterInset (pushes high-quality rolls closer to the max
        // edge instead of holding them off it) — a negative/absent perk value never increases inset.
        QualityRollModel base = new QualityRollModel(10, 0.2, 0.2, 0.2);
        assertEquals(0.1, base.withCraftMods(0.0, 0.0, 0.1).rollCenterInset(), 1e-12);
        // Floored at 0 rather than going negative for a large reduction.
        assertEquals(0.0, base.withCraftMods(0.0, 0.0, 5.0).rollCenterInset(), 1e-12);
        // A negative insetDelta must not INCREASE inset (Math.max(0.0, insetDelta) ignores it).
        assertEquals(0.2, base.withCraftMods(0.0, 0.0, -5.0).rollCenterInset(), 1e-12);
    }

    @Test
    void withCraftModsOfNoneReturnsAnEquivalentModel() {
        QualityRollModel base = new QualityRollModel(10, 0.2, 0.15, 0.1);
        QualityRollModel adjusted = base.withCraftMods(CraftRollMods.NONE);
        assertEquals(base, adjusted);
    }

    @Test
    void withCraftModsNeverThrowsForExtremeInputs() {
        QualityRollModel base = new QualityRollModel(10, 0.2, 0.2, 0.2);
        // A huge positive insetDelta reduces inset all the way to the floor (0), not up to 0.4999.
        assertEquals(0.0, base.withCraftMods(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
                .rollCenterInset(), 1e-12);
        // A huge negative insetDelta is ignored (Math.max(0.0, insetDelta)), so inset stays at base.
        assertEquals(0.2, base.withCraftMods(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
                .rollCenterInset(), 1e-12);
    }

    @Test
    void constructorRejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new QualityRollModel(-1, 0.5, 0.5, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new QualityRollModel(9, -0.1, 0.5, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new QualityRollModel(9, 0.5, -0.1, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new QualityRollModel(9, Double.NaN, 0.5, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new QualityRollModel(9, 0.5, Double.POSITIVE_INFINITY, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new QualityRollModel(9, 0.5, 0.5, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new QualityRollModel(9, 0.5, 0.5, 0.5)); // >= 0.5
        assertThrows(IllegalArgumentException.class, () -> new QualityRollModel(9, 0.5, 0.5, Double.NaN));
    }
}
