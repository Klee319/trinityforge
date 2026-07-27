package com.trinityforge.skilltree.runtime;

import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.PerkNaming;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativePerkServiceValidationTest {

    private static final SkillNode ROOT = node("A", null, null, 10, 1);
    private static final SkillNode CHILD = node("B", "A", null, 20, 2);
    private static final SkillNode EXCLUSIVE = node("C", "A", "route", 20, 1);
    private static final SkillNode EXCLUSIVE_SIBLING = node("D", "A", "route", 20, 1);
    private static final SkillNode EXCLUSIVE_CHILD = node("E", "C", "route", 30, 1);
    private static final SkillTree TREE = new SkillTree(
            "ARCHERY", "弓術", "BOW", "2,10", null,
            Map.of("A", ROOT, "B", CHILD, "C", EXCLUSIVE, "D", EXCLUSIVE_SIBLING,
                    "E", EXCLUSIVE_CHILD));

    @Test
    void reportsEligibleOnlyWhenEveryUnlockConditionIsMet() {
        Set<String> owned = Set.of(PerkNaming.perkId("ARCHERY", "A"));

        assertEquals(NativePerkService.UnlockResult.ELIGIBLE,
                NativePerkService.validateUnlock(TREE, CHILD, 20, 2, owned));
        assertEquals(NativePerkService.UnlockResult.LEVEL_TOO_LOW,
                NativePerkService.validateUnlock(TREE, CHILD, 19, 2, owned));
        assertEquals(NativePerkService.UnlockResult.INSUFFICIENT_POINTS,
                NativePerkService.validateUnlock(TREE, CHILD, 20, 1, owned));
    }

    @Test
    void reportsParentOwnershipAndExclusiveConflicts() {
        assertEquals(NativePerkService.UnlockResult.MISSING_PARENT,
                NativePerkService.validateUnlock(TREE, CHILD, 20, 2, Set.of()));

        Set<String> owned = Set.of(
                PerkNaming.perkId("ARCHERY", "A"),
                PerkNaming.perkId("ARCHERY", "D"));
        assertEquals(NativePerkService.UnlockResult.EXCLUSIVE_CONFLICT,
                NativePerkService.validateUnlock(TREE, EXCLUSIVE, 20, 2, owned));
    }

    @Test
    void allowsProgressionAlongTheAlreadySelectedExclusiveRoute() {
        Set<String> owned = Set.of(
                PerkNaming.perkId("ARCHERY", "A"),
                PerkNaming.perkId("ARCHERY", "C"));

        assertEquals(NativePerkService.UnlockResult.ELIGIBLE,
                NativePerkService.validateUnlock(TREE, EXCLUSIVE_CHILD, 30, 1, owned));
    }

    @Test
    void anyOfParentAllowsEitherRouteButStillRequiresOne() {
        SkillNode merge = new SkillNode(
                "MERGE", "MERGE", 40, SkillRole.GREEK,
                "C", List.of("E"), null, "STONE", 1, "",
                Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of());
        SkillTree tree = new SkillTree(
                "ARCHERY", "弓術", "BOW", "2,10", null,
                Map.of("C", EXCLUSIVE, "E", EXCLUSIVE_CHILD, "MERGE", merge));

        assertEquals(NativePerkService.UnlockResult.ELIGIBLE,
                NativePerkService.validateUnlock(tree, merge, 40, 1,
                        Set.of(PerkNaming.perkId("ARCHERY", "C"))));
        assertEquals(NativePerkService.UnlockResult.ELIGIBLE,
                NativePerkService.validateUnlock(tree, merge, 40, 1,
                        Set.of(PerkNaming.perkId("ARCHERY", "E"))));
        assertEquals(NativePerkService.UnlockResult.MISSING_PARENT,
                NativePerkService.validateUnlock(tree, merge, 40, 1, Set.of()));
    }

    @Test
    void reportsAlreadyUnlockedBeforeOtherChecks() {
        Set<String> owned = Set.of(PerkNaming.perkId("ARCHERY", "B"));

        assertEquals(NativePerkService.UnlockResult.ALREADY_UNLOCKED,
                NativePerkService.validateUnlock(TREE, CHILD, 0, 0, owned));
    }

    private static SkillNode node(String id, String parent, String group, int level, int cost) {
        return new SkillNode(id, id, level, SkillRole.MAIN, parent, group, "STONE", cost,
                "", Map.of(), Map.of(), List.of(), List.of(), List.of());
    }
}
