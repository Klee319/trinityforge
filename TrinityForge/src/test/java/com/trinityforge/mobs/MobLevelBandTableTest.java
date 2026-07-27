package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MobLevelBandTable}: floor resolution + empty/backward-compat fallback semantics, and the
 * one deliberate deviation from {@code TierTable} (level 0 must be a valid lookup/band key).
 */
class MobLevelBandTableTest {

    @Test
    void emptyTableAlwaysResolvesToEmpty() {
        MobLevelBandTable<Integer> table = MobLevelBandTable.empty();
        assertTrue(table.isEmpty());
        assertEquals(Optional.empty(), table.resolve(0));
        assertEquals(Optional.empty(), table.resolve(100));
    }

    @Test
    void nullOrEmptyMapYieldsEmptyTable() {
        assertTrue(MobLevelBandTable.of(null).isEmpty());
        assertTrue(MobLevelBandTable.of(Map.of()).isEmpty());
    }

    @Test
    void levelZeroBandIsReachable() {
        // TierTable rejects lookup key <= 0 by design (player-tier domain starts at 1); mob levels
        // legitimately start at 0, so this must resolve, not fall through to empty.
        MobLevelBandTable<String> table = MobLevelBandTable.of(Map.of(0, "band0"));
        assertEquals(Optional.of("band0"), table.resolve(0));
        assertEquals(Optional.of("band0"), table.resolve(5));
    }

    @Test
    void resolvesFloorEntryAtBandBoundaries() {
        MobLevelBandTable<String> table = MobLevelBandTable.of(Map.of(0, "band0", 20, "band20", 50, "band50"));
        // Exactly at the lower bound of a band.
        assertEquals(Optional.of("band0"), table.resolve(0));
        assertEquals(Optional.of("band20"), table.resolve(20));
        assertEquals(Optional.of("band50"), table.resolve(50));
        // Just below the next band's lower bound (still the previous band).
        assertEquals(Optional.of("band0"), table.resolve(19));
        assertEquals(Optional.of("band20"), table.resolve(49));
        // Above the highest band: keeps resolving to it indefinitely.
        assertEquals(Optional.of("band50"), table.resolve(999));
    }

    @Test
    void belowLowestDefinedBandResolvesToEmpty() {
        MobLevelBandTable<String> table = MobLevelBandTable.of(Map.of(10, "band10"));
        assertEquals(Optional.empty(), table.resolve(0));
        assertEquals(Optional.empty(), table.resolve(9));
        assertEquals(Optional.of("band10"), table.resolve(10));
    }

    @Test
    void negativeLevelAlwaysResolvesToEmpty() {
        MobLevelBandTable<String> table = MobLevelBandTable.of(Map.of(0, "band0"));
        assertEquals(Optional.empty(), table.resolve(-1));
    }
}
