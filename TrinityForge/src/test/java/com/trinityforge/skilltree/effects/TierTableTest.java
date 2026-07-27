package com.trinityforge.skilltree.effects;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TierTable}: floor resolution + empty/backward-compat fallback semantics. */
class TierTableTest {

    @Test
    void emptyTableAlwaysResolvesToEmpty() {
        TierTable<Integer> table = TierTable.empty();
        assertTrue(table.isEmpty());
        assertEquals(Optional.empty(), table.resolve(1));
        assertEquals(Optional.empty(), table.resolve(100));
    }

    @Test
    void nullOrEmptyMapYieldsEmptyTable() {
        assertTrue(TierTable.of(null).isEmpty());
        assertTrue(TierTable.of(Map.of()).isEmpty());
    }

    @Test
    void resolvesFloorEntry() {
        TierTable<String> table = TierTable.of(Map.of(1, "tier1", 3, "tier3", 5, "tier5"));
        assertEquals(Optional.of("tier1"), table.resolve(1));
        assertEquals(Optional.of("tier1"), table.resolve(2));
        assertEquals(Optional.of("tier3"), table.resolve(3));
        assertEquals(Optional.of("tier3"), table.resolve(4));
        assertEquals(Optional.of("tier5"), table.resolve(5));
        assertEquals(Optional.of("tier5"), table.resolve(99));
    }

    @Test
    void belowLowestDefinedTierResolvesToEmpty() {
        TierTable<String> table = TierTable.of(Map.of(3, "tier3"));
        assertEquals(Optional.empty(), table.resolve(1));
        assertEquals(Optional.empty(), table.resolve(2));
        assertEquals(Optional.of("tier3"), table.resolve(3));
    }

    @Test
    void nonPositiveTierAlwaysResolvesToEmpty() {
        TierTable<String> table = TierTable.of(Map.of(1, "tier1"));
        assertEquals(Optional.empty(), table.resolve(0));
        assertEquals(Optional.empty(), table.resolve(-1));
    }
}
