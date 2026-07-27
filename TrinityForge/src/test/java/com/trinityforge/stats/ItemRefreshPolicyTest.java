package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure refresh decision (item 1): whether an item must be re-assembled against live tables. */
class ItemRefreshPolicyTest {

    @Test
    void nonAddonItemNeverNeedsRefreshRegardlessOfGeneration() {
        assertFalse(ItemRefreshPolicy.needsRefresh(false, Optional.empty(), 5));
        assertFalse(ItemRefreshPolicy.needsRefresh(false, Optional.of(1), 5));
    }

    @Test
    void addonItemWithNoStoredGenerationNeedsRefresh() {
        // Pre-existing items assembled before the generation stamp existed carry no value at all.
        assertTrue(ItemRefreshPolicy.needsRefresh(true, Optional.empty(), 1));
    }

    @Test
    void addonItemWithStaleGenerationNeedsRefresh() {
        assertTrue(ItemRefreshPolicy.needsRefresh(true, Optional.of(1), 2));
    }

    @Test
    void addonItemWithCurrentGenerationDoesNotNeedRefresh() {
        assertFalse(ItemRefreshPolicy.needsRefresh(true, Optional.of(3), 3));
    }

    @Test
    void rejectsNullStoredGeneration() {
        assertThrows(NullPointerException.class, () -> ItemRefreshPolicy.needsRefresh(true, null, 1));
    }
}
