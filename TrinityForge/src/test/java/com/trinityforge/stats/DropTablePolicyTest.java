package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropTablePolicyTest {

    private static final String PROF = "mining";

    @Test
    void triggerRollHonorsPercentBoundary() {
        assertTrue(DropTablePolicy.triggerRoll(50.0, 0.49));
        assertFalse(DropTablePolicy.triggerRoll(50.0, 0.5));
        assertFalse(DropTablePolicy.triggerRoll(0.0, 0.0));
        assertFalse(DropTablePolicy.triggerRoll(Double.NaN, 0.0));
    }

    @Test
    void categoryUnreferencedInGateIndexIsOpen() {
        assertTrue(DropTablePolicy.categoryOpen(PROF, "tier1", Set.of(), Map.of()));
        assertTrue(DropTablePolicy.categoryOpen(PROF, "tier1", null, Map.of()));
    }

    @Test
    void categoryReferencedRequiresMatchingPerk() {
        Map<String, Set<String>> gate = Map.of("mining:tier1", Set.of("mining_perk_1"));
        assertFalse(DropTablePolicy.categoryOpen(PROF, "tier1", Set.of(), gate));
        assertFalse(DropTablePolicy.categoryOpen(PROF, "tier1", Set.of("other_perk"), gate));
        assertTrue(DropTablePolicy.categoryOpen(PROF, "tier1", Set.of("mining_perk_1"), gate));
    }

    @Test
    void itemLevelGateExcludesLockedEntryAndRedistributesWeight() {
        List<DropTableConfig.Entry> entries = List.of(
                new DropTableConfig.Entry("open_item", 1, 1),
                new DropTableConfig.Entry("locked_item", 9, 1));
        Map<String, Set<String>> gate = Map.of("mining:item:locked_item", Set.of("mining_perk_2"));

        // Without the perk: locked_item (weight 9) is excluded, so open_item (weight 1) is the only option
        // regardless of the roll value — the locked weight never contributes to the pool.
        Optional<DropTableConfig.Entry> drawn = DropTablePolicy.drawOpenEntry(PROF, entries, Set.of(), gate, 0.99);
        assertTrue(drawn.isPresent());
        assertEquals("open_item", drawn.get().item());
    }

    @Test
    void drawOpenEntryEmptyWhenEverythingLocked() {
        List<DropTableConfig.Entry> entries = List.of(new DropTableConfig.Entry("locked_item", 5, 1));
        Map<String, Set<String>> gate = Map.of("mining:item:locked_item", Set.of("mining_perk_2"));
        assertTrue(DropTablePolicy.drawOpenEntry(PROF, entries, Set.of(), gate, 0.0).isEmpty());
    }

    @Test
    void evaluateCategorySkipsWhenCategoryLocked() {
        DropTableConfig.Category category = new DropTableConfig.Category("tier1", "Tier1", 100.0,
                List.of(new DropTableConfig.Entry("x", 1, 1)), false);
        Map<String, Set<String>> gate = Map.of("mining:tier1", Set.of("perk"));
        assertTrue(DropTablePolicy.evaluateCategory(PROF, category, Set.of(), gate, 0.0, 0.0).isEmpty());
    }

    @Test
    void evaluateCategoryDrawsWhenTriggerHitsAndOpen() {
        DropTableConfig.Category category = new DropTableConfig.Category("tier1", "Tier1", 100.0,
                List.of(new DropTableConfig.Entry("x", 1, 1)), false);
        Optional<DropTableConfig.Entry> result =
                DropTablePolicy.evaluateCategory(PROF, category, Set.of(), Map.of(), 0.0, 0.0);
        assertTrue(result.isPresent());
        assertEquals("x", result.get().item());
    }

    @Test
    void evaluateCategoryMissesWhenTriggerFails() {
        DropTableConfig.Category category = new DropTableConfig.Category("tier1", "Tier1", 1.0,
                List.of(new DropTableConfig.Entry("x", 1, 1)), false);
        assertTrue(DropTablePolicy.evaluateCategory(PROF, category, Set.of(), Map.of(), 0.5, 0.0).isEmpty());
    }

    @Test
    void drawAcrossCategoriesPoolsOnlyOpenCategories() {
        Map<String, DropTableConfig.Category> categories = Map.of(
                "open_cat", new DropTableConfig.Category("open_cat", "Open", 0.0,
                        List.of(new DropTableConfig.Entry("a", 1, 1)), false),
                "locked_cat", new DropTableConfig.Category("locked_cat", "Locked", 0.0,
                        List.of(new DropTableConfig.Entry("b", 100, 1)), false));
        Map<String, Set<String>> gate = Map.of("fishing:locked_cat", Set.of("perk"));

        Optional<DropTableConfig.Entry> drawn =
                DropTablePolicy.drawAcrossCategories("fishing", categories, Set.of(), gate, 0.99);
        assertTrue(drawn.isPresent());
        assertEquals("a", drawn.get().item());
    }

    @Test
    void treasurePercentShiftsUpwardWithPositiveLuckAndClampsAt95() {
        assertEquals(15.0, DropTablePolicy.treasurePercent(15.0, 0.0), 1e-9);
        assertEquals(30.0, DropTablePolicy.treasurePercent(15.0, 1.0), 1e-9);
        assertEquals(95.0, DropTablePolicy.treasurePercent(15.0, 1000.0), 1e-9);
    }

    @Test
    void treasurePercentPassesThroughAdminZeroAndHundredUnclampedWhenLuckIsZero() {
        // 2026-07-23 verifier指摘⑤: luckTotal==0 のとき、管理者の treasure-percent: 0 / 100 設定は
        // [0.05, 95] クランプの対象外でそのまま実現できる（クランプはluckシフト適用後にのみ掛かる）。
        assertEquals(0.0, DropTablePolicy.treasurePercent(0.0, 0.0), 1e-9);
        assertEquals(100.0, DropTablePolicy.treasurePercent(100.0, 0.0), 1e-9);
    }

    @Test
    void treasurePercentNeverHitsExactZeroWithExtremeNegativeLuck() {
        double result = DropTablePolicy.treasurePercent(15.0, -9999.0);
        assertTrue(result >= 0.05, "even a huge negative luck must clamp to the 0.05% floor, never 0");
        assertEquals(0.05, result, 1e-9);
    }

    @Test
    void rollTreasureGroupHonorsBoundary() {
        assertTrue(DropTablePolicy.rollTreasureGroup(50.0, 0.49));
        assertFalse(DropTablePolicy.rollTreasureGroup(50.0, 0.5));
    }

    // ---- rollFishOutcome: 三択モデル化 (2026-07-23 仕様確定) ----

    @Test
    void rollFishOutcomeAlwaysTreasureWhenTreasurePercentIsHundred() {
        for (double roll : new double[] {0.0, 0.5, 0.999999}) {
            assertEquals(DropTablePolicy.FishOutcome.TREASURE,
                    DropTablePolicy.rollFishOutcome(100.0, 10.0, 0.0, roll));
        }
    }

    @Test
    void rollFishOutcomeAlwaysNormalFishWhenTreasureAndJunkAreZero() {
        for (double roll : new double[] {0.0, 0.5, 0.999999}) {
            assertEquals(DropTablePolicy.FishOutcome.NORMAL_FISH,
                    DropTablePolicy.rollFishOutcome(0.0, 0.0, 0.0, roll));
        }
    }

    @Test
    void rollFishOutcomeThreeWayBoundariesAtZeroLuck() {
        // treasure=5%, junk=10%, luck=0 -> [0,5)=TREASURE, [5,15)=JUNK, [15,100)=NORMAL_FISH.
        assertEquals(DropTablePolicy.FishOutcome.TREASURE,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, 0.0, 0.0));
        assertEquals(DropTablePolicy.FishOutcome.TREASURE,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, 0.0, 0.0499));
        assertEquals(DropTablePolicy.FishOutcome.JUNK,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, 0.0, 0.05));
        assertEquals(DropTablePolicy.FishOutcome.JUNK,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, 0.0, 0.1499));
        assertEquals(DropTablePolicy.FishOutcome.NORMAL_FISH,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, 0.0, 0.15));
        assertEquals(DropTablePolicy.FishOutcome.NORMAL_FISH,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, 0.0, 0.999));
    }

    @Test
    void rollFishOutcomePositiveLuckShrinksJunkAndNormalFishShareProportionally() {
        // treasure base=5%, junk base=10%; luck=1.0 -> treasureShifted=10% (×2), junk shifted =
        // 10 × (100-10)/(100-5) = 10 × 90/95 = 9.473...%. So [0,10)=TREASURE, [10,19.47)=JUNK,
        // [19.48,100)=NORMAL_FISH — both junk and normal-fish shrank vs. the 10%/85% baseline.
        assertEquals(DropTablePolicy.FishOutcome.TREASURE,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, 1.0, 0.0999));
        assertEquals(DropTablePolicy.FishOutcome.JUNK,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, 1.0, 0.10));
        assertEquals(DropTablePolicy.FishOutcome.JUNK,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, 1.0, 0.1940));
        assertEquals(DropTablePolicy.FishOutcome.NORMAL_FISH,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, 1.0, 0.1950));
    }

    @Test
    void rollFishOutcomeNegativeLuckGrowsJunkAndNormalFishShareProportionally() {
        // treasure base=5%, junk base=10%; luck=-0.5 -> treasureShifted=2.5%, junk shifted =
        // 10 × (100-2.5)/(100-5) = 10 × 97.5/95 = 10.263...% > the 10% baseline (junk/fish grow
        // as treasure shrinks).
        assertEquals(DropTablePolicy.FishOutcome.TREASURE,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, -0.5, 0.0249));
        assertEquals(DropTablePolicy.FishOutcome.JUNK,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, -0.5, 0.025));
        assertEquals(DropTablePolicy.FishOutcome.JUNK,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, -0.5, 0.1275));
        assertEquals(DropTablePolicy.FishOutcome.NORMAL_FISH,
                DropTablePolicy.rollFishOutcome(5.0, 10.0, -0.5, 0.1280));
    }

    @Test
    void rollFishOutcomeJunkIsZeroWhenTreasureBaseIsAlreadyHundred() {
        // treasureBase>=100 means there was never a non-treasure slice to begin with -> junk'=0
        // regardless of the configured junk base (luck==0 so treasureShifted passes through at 100).
        assertEquals(DropTablePolicy.FishOutcome.TREASURE,
                DropTablePolicy.rollFishOutcome(100.0, 50.0, 0.0, 0.999));
    }
}
