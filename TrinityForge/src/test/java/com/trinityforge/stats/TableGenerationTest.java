package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TableGeneration} is the monotonic stamp consumed by {@link ItemRefreshPolicy}. */
class TableGenerationTest {

    @Test
    void startsWithPositiveProcessGeneration() {
        assertTrue(new TableGeneration().current() > 0);
    }

    @Test
    void bumpIncrementsAndReturnsTheNewValue() {
        TableGeneration generation = new TableGeneration(41);
        int bumped = generation.bump();
        assertEquals(42, bumped);
        assertEquals(42, generation.current());
    }

    @Test
    void repeatedBumpsAreMonotonic() {
        TableGeneration generation = new TableGeneration(100);
        generation.bump();
        generation.bump();
        generation.bump();
        assertEquals(103, generation.current());
    }
}
