package com.trinityforge.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link StatCategory} parsing, including the 2026-07-23 stat-gate-overhaul §2.3 7-category expansion
 * and the {@code "support"} → {@link StatCategory#UTILITY} backward-compat alias.
 */
class StatCategoryTest {

    @Test
    @DisplayName("\"support\" (legacy) parses as UTILITY")
    void supportIsBackwardCompatAliasForUtility() {
        assertEquals(StatCategory.UTILITY, StatCategory.parse("support"));
        assertEquals(StatCategory.UTILITY, StatCategory.parse("補助"));
    }

    @Test
    @DisplayName("\"utility\" parses directly as UTILITY")
    void utilityParsesDirectly() {
        assertEquals(StatCategory.UTILITY, StatCategory.parse("utility"));
    }

    @Test
    @DisplayName("new craft / gathering categories parse correctly")
    void newCategoriesParse() {
        assertEquals(StatCategory.CRAFT, StatCategory.parse("craft"));
        assertEquals(StatCategory.GATHERING, StatCategory.parse("gathering"));
    }

    @Test
    @DisplayName("pre-existing categories are unaffected")
    void existingCategoriesUnaffected() {
        assertEquals(StatCategory.ATTACK, StatCategory.parse("attack"));
        assertEquals(StatCategory.DEFENSE, StatCategory.parse("defense"));
        assertEquals(StatCategory.ARS, StatCategory.parse("ars"));
        assertEquals(StatCategory.OTHER, StatCategory.parse("other"));
        assertEquals(StatCategory.OTHER, StatCategory.parse(null));
        assertEquals(StatCategory.OTHER, StatCategory.parse(""));
    }

    @Test
    @DisplayName("configId round-trips for every category")
    void configIdRoundTrips() {
        for (StatCategory category : StatCategory.values()) {
            assertEquals(category, StatCategory.parse(category.configId()), category.name());
        }
    }
}
