package com.trinityforge.gacha;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GachaDrawTest {

    /** A {@link Random} stub whose {@code nextInt(bound)} always returns a fixed value, so boundary
     * behaviour of the weighted cumulative walk can be pinned down exactly instead of relying on a
     * real PRNG's sequence. */
    private static Random fixed(int nextIntValue) {
        return new Random() {
            @Override
            public int nextInt(int bound) {
                return nextIntValue;
            }
        };
    }

    @Test
    void rollZeroPicksFirstEntry() {
        GachaPool pool = new GachaPool("standard", List.of(
                new GachaEntry("common", 5, 1, false),
                new GachaEntry("rare", 5, 1, false)));

        GachaEntry drawn = GachaDraw.draw(pool, fixed(0));

        assertEquals("common", drawn.itemId());
    }

    @Test
    void rollAtFirstEntryUpperBoundaryStillPicksFirstEntry() {
        GachaPool pool = new GachaPool("standard", List.of(
                new GachaEntry("common", 5, 1, false),
                new GachaEntry("rare", 5, 1, false)));

        // weights [5, 5] -> cumulative bounds are [0,5) -> common, [5,10) -> rare.
        GachaEntry drawn = GachaDraw.draw(pool, fixed(4));

        assertEquals("common", drawn.itemId());
    }

    @Test
    void rollJustPastFirstBoundaryPicksSecondEntry() {
        GachaPool pool = new GachaPool("standard", List.of(
                new GachaEntry("common", 5, 1, false),
                new GachaEntry("rare", 5, 1, false)));

        GachaEntry drawn = GachaDraw.draw(pool, fixed(5));

        assertEquals("rare", drawn.itemId());
    }

    @Test
    void rollAtLastValidValuePicksLastEntry() {
        GachaPool pool = new GachaPool("standard", List.of(
                new GachaEntry("common", 5, 1, false),
                new GachaEntry("rare", 5, 1, false)));

        GachaEntry drawn = GachaDraw.draw(pool, fixed(9));

        assertEquals("rare", drawn.itemId());
    }

    @Test
    void heavilyWeightedEntryDominatesASeededDistribution() {
        GachaPool pool = new GachaPool("standard", List.of(
                new GachaEntry("common", 95, 1, false),
                new GachaEntry("rare", 5, 1, false)));

        Random rng = new Random(42);
        int commonCount = 0;
        int rareCount = 0;
        for (int i = 0; i < 10_000; i++) {
            GachaEntry drawn = GachaDraw.draw(pool, rng);
            if (drawn.itemId().equals("common")) {
                commonCount++;
            } else {
                rareCount++;
            }
        }

        assertEquals(10_000, commonCount + rareCount);
        // With a 95/5 weight split over 10,000 draws, the common entry must overwhelmingly dominate;
        // a generous band avoids flakiness while still catching a broken weighting.
        assertTrue(commonCount > 9_000, "expected common to dominate, got " + commonCount);
        assertTrue(rareCount > 0 && rareCount < 1_000, "expected some but few rares, got " + rareCount);
    }

    @Test
    void seededRandomIsDeterministicAcrossRuns() {
        GachaPool pool = new GachaPool("standard", List.of(
                new GachaEntry("a", 1, 1, false),
                new GachaEntry("b", 1, 1, false),
                new GachaEntry("c", 1, 1, false)));

        GachaEntry first = GachaDraw.draw(pool, new Random(1234));
        GachaEntry second = GachaDraw.draw(pool, new Random(1234));

        assertEquals(first.itemId(), second.itemId(), "same seed must reproduce the same draw");
    }

    @Test
    void emptyPoolThrows() {
        GachaPool pool = new GachaPool("empty", List.of());

        assertThrows(IllegalStateException.class, () -> GachaDraw.draw(pool, new Random(1)));
    }

    @Test
    void nullPoolOrRandomThrows() {
        GachaPool pool = new GachaPool("standard", List.of(new GachaEntry("a", 1, 1, false)));

        assertThrows(NullPointerException.class, () -> GachaDraw.draw(null, new Random(1)));
        assertThrows(NullPointerException.class, () -> GachaDraw.draw(pool, null));
    }

    // --- rarestEntries / drawRarest ---

    @Test
    void rarestEntriesReturnsOnlyMinimumWeightEntries() {
        GachaEntry common = new GachaEntry("common", 90, 1, false);
        GachaEntry rare = new GachaEntry("rare", 10, 1, false);
        GachaPool pool = new GachaPool("standard", List.of(common, rare));

        assertEquals(List.of(rare), GachaDraw.rarestEntries(pool));
    }

    @Test
    void rarestEntriesIncludesAllTiedMinimumWeightEntries() {
        GachaEntry rareA = new GachaEntry("rareA", 5, 1, false);
        GachaEntry rareB = new GachaEntry("rareB", 5, 1, false);
        GachaEntry common = new GachaEntry("common", 90, 1, false);
        GachaPool pool = new GachaPool("standard", List.of(rareA, rareB, common));

        assertEquals(List.of(rareA, rareB), GachaDraw.rarestEntries(pool));
    }

    @Test
    void isRarestEntryTrueOnlyForMinimumWeightEntries() {
        GachaEntry common = new GachaEntry("common", 90, 1, false);
        GachaEntry rare = new GachaEntry("rare", 10, 1, false);
        GachaPool pool = new GachaPool("standard", List.of(common, rare));

        assertTrue(GachaDraw.isRarestEntry(pool, rare));
        assertEquals(false, GachaDraw.isRarestEntry(pool, common));
    }

    @Test
    void drawRarestAlwaysPicksAMinimumWeightEntry() {
        GachaEntry common = new GachaEntry("common", 90, 1, false);
        GachaEntry rareA = new GachaEntry("rareA", 5, 1, false);
        GachaEntry rareB = new GachaEntry("rareB", 5, 1, false);
        GachaPool pool = new GachaPool("standard", List.of(common, rareA, rareB));

        Random rng = new Random(7);
        for (int i = 0; i < 100; i++) {
            GachaEntry drawn = GachaDraw.drawRarest(pool, rng);
            assertTrue(drawn == rareA || drawn == rareB, "drawRarest must never pick the common entry");
        }
    }

    // --- drawWithPity ---

    @Test
    void pityDisabledWhenThresholdIsZeroNeverForcesRarest() {
        GachaEntry common = new GachaEntry("common", 999, 1, false);
        GachaEntry rare = new GachaEntry("rare", 1, 1, false);
        GachaPool pool = new GachaPool("standard", List.of(common, rare), 0);

        // Even with a huge running counter, a disabled pity (threshold<=0) must never force the
        // rarest entry; the counter still tracks non-rarest streaks, it's just never consulted.
        GachaDraw.PityDraw outcome = GachaDraw.drawWithPity(pool, fixed(0), 1_000_000);

        assertEquals("common", outcome.entry().itemId());
        assertEquals(1_000_001, outcome.updatedPityCount());
        assertTrue(!outcome.pityTriggered());
    }

    @Test
    void pityIncrementsCounterOnNonRarestDraw() {
        // Distinct weights so "common" is unambiguously not the rarest entry.
        GachaEntry commonHeavy = new GachaEntry("common", 95, 1, false);
        GachaEntry rareLight = new GachaEntry("rare", 5, 1, false);
        GachaPool pool = new GachaPool("standard", List.of(commonHeavy, rareLight), 10);

        GachaDraw.PityDraw outcome = GachaDraw.drawWithPity(pool, fixed(0), 3);

        assertEquals("common", outcome.entry().itemId());
        assertEquals(4, outcome.updatedPityCount());
        assertTrue(!outcome.pityTriggered());
    }

    @Test
    void pityResetsCounterOnNaturalRarestHit() {
        GachaEntry commonHeavy = new GachaEntry("common", 95, 1, false);
        GachaEntry rareLight = new GachaEntry("rare", 5, 1, false);
        GachaPool pool = new GachaPool("standard", List.of(commonHeavy, rareLight), 10);

        // roll 99 -> falls in the rare cumulative bucket [95,100).
        GachaDraw.PityDraw outcome = GachaDraw.drawWithPity(pool, fixed(99), 3);

        assertEquals("rare", outcome.entry().itemId());
        assertEquals(0, outcome.updatedPityCount());
        assertTrue(!outcome.pityTriggered(), "natural hit, not a forced ceiling draw");
    }

    @Test
    void pityForcesRarestWhenThresholdReached() {
        GachaEntry commonHeavy = new GachaEntry("common", 95, 1, false);
        GachaEntry rareLight = new GachaEntry("rare", 5, 1, false);
        GachaPool pool = new GachaPool("standard", List.of(commonHeavy, rareLight), 10);

        // currentPityCount=9, threshold=10 -> 9+1 >= 10 forces the rarest entry regardless of roll.
        GachaDraw.PityDraw outcome = GachaDraw.drawWithPity(pool, fixed(0), 9);

        assertEquals("rare", outcome.entry().itemId());
        assertEquals(0, outcome.updatedPityCount());
        assertTrue(outcome.pityTriggered());
    }

    @Test
    void pityNegativeCurrentCountIsTreatedAsZero() {
        GachaEntry commonHeavy = new GachaEntry("common", 95, 1, false);
        GachaEntry rareLight = new GachaEntry("rare", 5, 1, false);
        GachaPool pool = new GachaPool("standard", List.of(commonHeavy, rareLight), 10);

        GachaDraw.PityDraw outcome = GachaDraw.drawWithPity(pool, fixed(0), -5);

        assertEquals("common", outcome.entry().itemId());
        assertEquals(1, outcome.updatedPityCount());
    }
}
