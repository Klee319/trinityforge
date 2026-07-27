package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Negative levels must not produce negative (healing) damage (cross-review MEDIUM-6). */
class DefaultDamageResolverNegLevelTest {

    @Test
    void negativeLevelIsClampedToZero() {
        DefaultDamageResolver resolver = new DefaultDamageResolver(1.0, 1.0, 0.05);
        assertEquals(10.0, resolver.physicalDefaultDamage(10, -50), 1e-9);
        // symmetric: the magical component clamps negative levels the same way
        assertEquals(10.0, resolver.magicalDefaultDamage(10, -50), 1e-9);
    }
}
