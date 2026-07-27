package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-tests {@link RollHash#standardNormal}: a deterministic N(0,1) sample keyed by an item's rollSeed + a
 * stat key. The roll must be reproducible (same seed + key -> same value) so a rolled stat can be re-derived
 * live without baking, and distinct keys / seeds must roll independently.
 */
class RollHashTest {

    @Test
    void sameSeedAndKeyAlwaysYieldTheSameSample() {
        assertEquals(RollHash.standardNormal(42L, "attack_power"),
                RollHash.standardNormal(42L, "attack_power"), 0.0);
    }

    @Test
    void differentKeysOnTheSameItemRollIndependently() {
        assertNotEquals(RollHash.standardNormal(42L, "attack_power"),
                RollHash.standardNormal(42L, "crit_chance"));
    }

    @Test
    void differentSeedsForTheSameKeyRollIndependently() {
        assertNotEquals(RollHash.standardNormal(1L, "attack_power"),
                RollHash.standardNormal(2L, "attack_power"));
    }

    @Test
    void everySampleIsFinite() {
        long[] seeds = {0L, 1L, -1L, 42L, Long.MAX_VALUE, Long.MIN_VALUE, 999_999_999L};
        String[] keys = {"attack_power", "crit_chance", "phys_resistance", "", "a"};
        for (long seed : seeds) {
            for (String key : keys) {
                double z = RollHash.standardNormal(seed, key);
                assertTrue(Double.isFinite(z), "Z must be finite: seed=" + seed + " key=" + key + " z=" + z);
            }
        }
    }

    @Test
    void theMeanOfManyKeysIsCloseToZero() {
        // A crude sanity check that the draw is a genuine standard normal, not a constant: average many
        // independent per-key draws on one item and expect the mean near 0 (well within a loose band).
        double sum = 0;
        int n = 2000;
        for (int i = 0; i < n; i++) {
            sum += RollHash.standardNormal(7L, "stat_" + i);
        }
        double mean = sum / n;
        assertTrue(Math.abs(mean) < 0.15, "mean of 2000 draws should be near 0, was " + mean);
    }

    @Test
    void nullKeyIsHandledDeterministically() {
        assertEquals(RollHash.standardNormal(7L, null), RollHash.standardNormal(7L, null), 0.0);
        assertTrue(Double.isFinite(RollHash.standardNormal(7L, null)));
    }
}
