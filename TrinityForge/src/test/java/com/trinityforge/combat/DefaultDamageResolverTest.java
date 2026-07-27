package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Gear-independent level scaling of default damage, symmetric across physical and magical. */
class DefaultDamageResolverTest {

    private static final double EPS = 1e-9;

    @Test
    void levelZeroIsBaseTimesCoefficient() {
        DefaultDamageResolver resolver = new DefaultDamageResolver(1.0, 1.0, 0.05);
        assertEquals(10.0, resolver.physicalDefaultDamage(10, 0), EPS);
    }

    @Test
    void scalesLinearlyWithLevel() {
        DefaultDamageResolver resolver = new DefaultDamageResolver(1.0, 1.0, 0.05);
        // 10 * (1 + 0.05 * 10) = 15
        assertEquals(15.0, resolver.physicalDefaultDamage(10, 10), EPS);
    }

    @Test
    void physicalCoefficientScalesEverything() {
        DefaultDamageResolver resolver = new DefaultDamageResolver(2.0, 1.0, 0.0);
        assertEquals(20.0, resolver.physicalDefaultDamage(10, 5), EPS);
    }

    @Test
    void magicalUsesSameLevelCurve() {
        DefaultDamageResolver resolver = new DefaultDamageResolver(1.0, 1.0, 0.05);
        // symmetric with physical: 10 * (1 + 0.05 * 10) * 1.0 = 15
        assertEquals(15.0, resolver.magicalDefaultDamage(10, 10), EPS);
    }

    @Test
    void magicalCoefficientIsIndependentOfPhysical() {
        DefaultDamageResolver resolver = new DefaultDamageResolver(2.0, 3.0, 0.0);
        assertEquals(30.0, resolver.magicalDefaultDamage(10, 4), EPS);
        assertEquals(20.0, resolver.physicalDefaultDamage(10, 4), EPS);
    }
}
