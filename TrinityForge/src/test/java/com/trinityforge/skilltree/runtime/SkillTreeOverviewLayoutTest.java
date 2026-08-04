package com.trinityforge.skilltree.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillTreeOverviewLayout}: 一覧モード(2026-08-04新設)の純粋なレイアウトの回帰。
 * {@code NativeSkillTreeCanvasTest} と同じく、Bukkit非依存のまま実際の16スキルID順で検証する。
 */
class SkillTreeOverviewLayoutTest {

    private static final List<String> SKILLS = List.of(
            "POWER", "SMITHING", "ENCHANTING", "ALCHEMY",
            "MINING", "WOODCUTTING", "DIGGING", "FARMING",
            "LIGHT_WEAPONS", "HEAVY_WEAPONS", "FISHING", "ARCHERY",
            "LIGHT_ARMOR", "HEAVY_ARMOR", "ARS_MAGIC", "ARS_SMITHING");

    @Test
    void everySkillGetsAUniqueSlotWithinTheFiftyFourSlotInventory() {
        Map<String, Integer> assigned = SkillTreeOverviewLayout.assign(SKILLS);

        assertEquals(SKILLS.size(), assigned.size(), "no skill should be dropped (16 <= capacity)");
        assertTrue(SkillTreeOverviewLayout.capacity() >= SKILLS.size());

        Set<Integer> slots = new HashSet<>();
        for (String skill : SKILLS) {
            int slot = assigned.get(skill);
            assertTrue(slots.add(slot), "duplicate slot for " + skill);
            assertTrue(slot >= 0 && slot < 54, "slot out of the 54-slot inventory: " + slot);
            // トグルボタン専用のスロットとは重ならない。
            assertTrue(slot != SkillTreeOverviewLayout.TOGGLE_SLOT);
        }
    }

    @Test
    void skillForSlotResolvesTheAssignedSkillAndEmptyForUnassignedSlots() {
        Map<String, Integer> assigned = SkillTreeOverviewLayout.assign(SKILLS);
        int miningSlot = assigned.get("MINING");

        assertEquals("MINING", SkillTreeOverviewLayout.skillForSlot(miningSlot, SKILLS).orElseThrow());
        assertTrue(SkillTreeOverviewLayout.skillForSlot(SkillTreeOverviewLayout.TOGGLE_SLOT, SKILLS).isEmpty());
    }

    @Test
    void skillsBeyondCapacityAreNotAssigned() {
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < SkillTreeOverviewLayout.capacity() + 5; i++) {
            tooMany.add("SKILL_" + i);
        }

        Map<String, Integer> assigned = SkillTreeOverviewLayout.assign(tooMany);

        assertEquals(SkillTreeOverviewLayout.capacity(), assigned.size());
    }
}
