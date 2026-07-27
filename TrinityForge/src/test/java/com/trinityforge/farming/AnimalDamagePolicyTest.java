package com.trinityforge.farming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AnimalDamagePolicy}: victim eligibility and the damage-multiplier fail-safes. */
class AnimalDamagePolicyTest {

    @Test
    void animalNotMonsterIsEligible() {
        assertTrue(AnimalDamagePolicy.eligibleVictim(true, false));
    }

    @Test
    void nonAnimalIsNotEligible() {
        assertFalse(AnimalDamagePolicy.eligibleVictim(false, false));
    }

    @Test
    void hostileAnimalsLikeMobsAreExcludedEvenIfAnimalsInterface() {
        // Defensive case: something implementing both Animals and Monster must still be excluded.
        assertFalse(AnimalDamagePolicy.eligibleVictim(true, true));
    }

    @Test
    void multiplyAppliesConfiguredMultiplier() {
        assertEquals(16.0, AnimalDamagePolicy.multiply(4.0, 4.0));
    }

    @Test
    void multiplyIsNoOpForNonPositiveMultiplier() {
        assertEquals(4.0, AnimalDamagePolicy.multiply(4.0, 0.0));
        assertEquals(4.0, AnimalDamagePolicy.multiply(4.0, -2.0));
    }

    @Test
    void multiplyIsNoOpForNonFiniteMultiplier() {
        assertEquals(4.0, AnimalDamagePolicy.multiply(4.0, Double.NaN));
        assertEquals(4.0, AnimalDamagePolicy.multiply(4.0, Double.POSITIVE_INFINITY));
    }

    @Test
    void multiplyIsNoOpForNonPositiveDamage() {
        assertEquals(0.0, AnimalDamagePolicy.multiply(0.0, 4.0));
        assertEquals(-1.0, AnimalDamagePolicy.multiply(-1.0, 4.0));
    }

    @Test
    void multipliedResultNeverNegative() {
        assertTrue(AnimalDamagePolicy.multiply(1.0, 4.0) >= 0.0);
    }
}
