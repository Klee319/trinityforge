package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link DodgeResolver#capped} (B3 sibling for 回避率, #3): an uncapped dodge chance could
 * reach 1.0 and constitute permanent dodge-immunity, mirroring the {@link DefenseStats#cappedMitigation}
 * fix for the mitigation rates.
 */
class DodgeResolverTest {

    @Test
    void cappedClampsChanceAboveMaxBeforeDelegating() {
        // ALWAYS dodges any positive chance, but capped() clamps 1.0 down to 0.0 first (max=0.0),
        // so the delegate never sees a positive chance and never dodges.
        DodgeResolver resolver = DodgeResolver.capped(DodgeResolver.ALWAYS, 0.0);
        assertFalse(resolver.rolls(1.0));
    }

    @Test
    void cappedAllowsChanceUpToTheMax() {
        // ALWAYS dodges any positive chance; a chance already <= max passes through unclamped.
        DodgeResolver resolver = DodgeResolver.capped(DodgeResolver.ALWAYS, 0.9);
        assertTrue(resolver.rolls(0.9));
        assertTrue(resolver.rolls(0.5));
    }

    @Test
    void cappedPreventsPermanentDodgeImmunityAtChanceOne() {
        // The exact B3 failure mode this closes: an additively-stacked dodge chance of 1.0 (permanent,
        // unconditional dodge) must be reduced toward the configured ceiling before the roll. Capping
        // down to exactly 0 makes RANDOM's non-positive-chance guard deterministically never dodge.
        DodgeResolver fullyCapped = DodgeResolver.capped(DodgeResolver.RANDOM, 0.0);
        assertFalse(fullyCapped.rolls(1.0));
    }

    @Test
    void cappedTreatsNonFiniteMaxChanceAsUncapped() {
        DodgeResolver resolver = DodgeResolver.capped(DodgeResolver.ALWAYS, Double.NaN);
        assertTrue(resolver.rolls(1.0));
    }
}
