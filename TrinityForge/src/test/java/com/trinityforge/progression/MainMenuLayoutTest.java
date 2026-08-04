package com.trinityforge.progression;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MainMenuLayout}: {@code /tf menu} の純粋なレイアウト・クリック解決の回帰
 * ({@code AchievementCanvasTest} と同じ「Bukkit非依存の純粋関数をテストする」方針)。
 */
class MainMenuLayoutTest {

    @Test
    void allSixExpectedItemsArePresentWithUniqueSlots() {
        List<MainMenuLayout.Item> items = MainMenuLayout.items();
        assertEquals(6, items.size());

        Set<String> ids = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        for (MainMenuLayout.Item item : items) {
            assertTrue(ids.add(item.id()), "duplicate id: " + item.id());
            assertTrue(slots.add(item.slot()), "duplicate slot: " + item.slot());
            assertTrue(item.slot() >= 0 && item.slot() < 54,
                    "slot out of the 54-slot inventory: " + item.slot());
        }
        assertEquals(Set.of(MainMenuLayout.ROLE, MainMenuLayout.STATUS, MainMenuLayout.SKILLS,
                MainMenuLayout.ACHIEVEMENT, MainMenuLayout.COLLECTION, MainMenuLayout.SETTINGS), ids);
    }

    @Test
    void itemForSlotResolvesKnownSlotsAndEmptyForUnknown() {
        MainMenuLayout.Item role = MainMenuLayout.itemForId(MainMenuLayout.ROLE).orElseThrow();
        assertEquals(role, MainMenuLayout.itemForSlot(role.slot()).orElseThrow());
        assertTrue(MainMenuLayout.itemForSlot(999).isEmpty());
    }

    @Test
    void resolveClickDefaultsToAvailableWhenFlagMissing() {
        MainMenuLayout.Item skills = MainMenuLayout.itemForId(MainMenuLayout.SKILLS).orElseThrow();

        MainMenuLayout.Resolution resolution = MainMenuLayout.resolveClick(skills.slot(), Map.of());

        assertTrue(resolution.matched());
        assertTrue(resolution.available());
        assertEquals(MainMenuLayout.SKILLS, resolution.id());
    }

    @Test
    void resolveClickHonoursExplicitUnavailableFlag() {
        MainMenuLayout.Item role = MainMenuLayout.itemForId(MainMenuLayout.ROLE).orElseThrow();

        MainMenuLayout.Resolution resolution = MainMenuLayout.resolveClick(
                role.slot(), Map.of(MainMenuLayout.ROLE, false));

        assertTrue(resolution.matched());
        assertFalse(resolution.available());
        assertEquals(MainMenuLayout.ROLE, resolution.id());
    }

    @Test
    void resolveClickOnEmptySlotDoesNotMatch() {
        MainMenuLayout.Resolution resolution = MainMenuLayout.resolveClick(0, Map.of());

        assertFalse(resolution.matched());
        assertFalse(resolution.available());
    }
}
