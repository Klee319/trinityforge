package com.trinityforge.gacha;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link GachaRateUp}: minimum-weight-entry boost approximation for the {@code gacha-rate-up} effect. */
class GachaRateUpTest {

    private static GachaPool poolOf(GachaEntry... entries) {
        return new GachaPool("test-pool", List.of(entries));
    }

    @Test
    void zeroOrNegativeBonusReturnsSamePoolUnchanged() {
        GachaPool pool = poolOf(new GachaEntry("common", 90, 1, false), new GachaEntry("rare", 10, 1, false));

        assertSame(pool, GachaRateUp.applyRateUp(pool, 0.0));
        assertSame(pool, GachaRateUp.applyRateUp(pool, -5.0));
    }

    @Test
    void boostsOnlyTheMinimumWeightEntries() {
        GachaEntry common = new GachaEntry("common", 90, 1, false);
        GachaEntry rare = new GachaEntry("rare", 10, 1, false);
        GachaPool pool = poolOf(common, rare);

        GachaPool boosted = GachaRateUp.applyRateUp(pool, 0.5); // +50% (fraction 0.5) -> 10 * 1.5 = 15

        assertEquals(90, boosted.entries().get(0).weight());
        assertEquals(15, boosted.entries().get(1).weight());
        assertEquals(pool.id(), boosted.id());
    }

    @Test
    void boostsAllEntriesTiedForMinimumWeight() {
        GachaEntry rareA = new GachaEntry("rareA", 5, 1, false);
        GachaEntry rareB = new GachaEntry("rareB", 5, 1, false);
        GachaEntry common = new GachaEntry("common", 90, 1, false);
        GachaPool pool = poolOf(rareA, rareB, common);

        GachaPool boosted = GachaRateUp.applyRateUp(pool, 1.0); // +100% (fraction 1.0) -> 5 * 2 = 10

        assertEquals(10, boosted.entries().get(0).weight());
        assertEquals(10, boosted.entries().get(1).weight());
        assertEquals(90, boosted.entries().get(2).weight());
    }

    @Test
    void boostedWeightNeverDropsBelowOne() {
        GachaEntry rare = new GachaEntry("rare", 1, 1, false);
        GachaPool pool = poolOf(rare);

        // Tiny bonus that rounds down to the same weight must still stay >= 1 (GachaEntry invariant).
        GachaPool boosted = GachaRateUp.applyRateUp(pool, 0.0001);

        assertEquals(1, boosted.entries().get(0).weight());
    }

    @Test
    void emptyPoolReturnedUnchanged() {
        GachaPool empty = poolOf();
        assertSame(empty, GachaRateUp.applyRateUp(empty, 0.5));
    }

    @Test
    void boostedPoolPreservesPityThreshold() {
        GachaEntry common = new GachaEntry("common", 90, 1, false);
        GachaEntry rare = new GachaEntry("rare", 10, 1, false);
        GachaPool pool = new GachaPool("test-pool", List.of(common, rare), 30);

        GachaPool boosted = GachaRateUp.applyRateUp(pool, 0.5);

        assertEquals(30, boosted.pityThreshold(),
                "pity ceiling is a config-defined property and must survive the rate-up boost copy");
    }
}
