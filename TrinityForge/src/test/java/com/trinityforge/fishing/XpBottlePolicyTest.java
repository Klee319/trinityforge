package com.trinityforge.fishing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link XpBottlePolicy}: level/exp -> total-XP conversion (against known vanilla reference totals)
 * and the xp-bottle-store-unlock store-amount clamp.
 */
class XpBottlePolicyTest {

    @Test
    void totalExperienceAtLevelMatchesKnownVanillaReferenceValues() {
        // Reference totals from the standard Minecraft XP-level formula (level<=15 quadratic,
        // 16-30 gentler quadratic, 31+ steeper quadratic).
        assertEquals(0, XpBottlePolicy.totalExperienceAtLevel(0));
        assertEquals(352, XpBottlePolicy.totalExperienceAtLevel(16));
        assertEquals(394, XpBottlePolicy.totalExperienceAtLevel(17));
        assertEquals(1395, XpBottlePolicy.totalExperienceAtLevel(30));
        assertEquals(1507, XpBottlePolicy.totalExperienceAtLevel(31));
    }

    @Test
    void totalExperienceAtLevelNegativeLevelClampsToZero() {
        assertEquals(0, XpBottlePolicy.totalExperienceAtLevel(-5));
    }

    @Test
    void experienceToNextLevelMatchesKnownVanillaReferenceValues() {
        assertEquals(7, XpBottlePolicy.experienceToNextLevel(0));
        assertEquals(37, XpBottlePolicy.experienceToNextLevel(15));
        assertEquals(42, XpBottlePolicy.experienceToNextLevel(16));
        assertEquals(121, XpBottlePolicy.experienceToNextLevel(31));
    }

    @Test
    void totalExperienceAddsFractionalProgressWithinLevel() {
        // Level 10 total is 10*10+6*10=160; bar length at level 10 is 2*10+7=27; half progress -> +13(rounded).
        int expected = 160 + Math.round(0.5f * 27);
        assertEquals(expected, XpBottlePolicy.totalExperience(10, 0.5f));
    }

    @Test
    void totalExperienceTreatsNonFiniteOrNegativeExpAsZeroProgress() {
        assertEquals(160, XpBottlePolicy.totalExperience(10, Float.NaN));
        assertEquals(160, XpBottlePolicy.totalExperience(10, -0.3f));
        assertEquals(160, XpBottlePolicy.totalExperience(10, 0.0f));
    }

    @Test
    void clampStoreAmountNeverExceedsAvailable() {
        assertEquals(50, XpBottlePolicy.clampStoreAmount(50, 100));
    }

    @Test
    void clampStoreAmountUsesConfiguredAmountWhenAvailableIsLarger() {
        assertEquals(100, XpBottlePolicy.clampStoreAmount(500, 100));
    }

    @Test
    void clampStoreAmountZeroWhenNothingAvailableOrConfigured() {
        assertEquals(0, XpBottlePolicy.clampStoreAmount(0, 100));
        assertEquals(0, XpBottlePolicy.clampStoreAmount(500, 0));
        assertEquals(0, XpBottlePolicy.clampStoreAmount(-5, 100));
        assertEquals(0, XpBottlePolicy.clampStoreAmount(500, -5));
    }
}
