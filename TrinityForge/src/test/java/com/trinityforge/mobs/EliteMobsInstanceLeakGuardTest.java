package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the release operation used when EliteMobs destroys an instanced dungeon.
 * The real EliteMobs API is loaded reflectively, so this test keeps the ownership
 * contract verifiable without requiring EliteMobs on the test classpath.
 */
class EliteMobsInstanceLeakGuardTest {

    @Test
    void removesOnlyDestroyedDungeonInstancesFromTheRetainingSet() {
        Object endedDungeon = new FakeDungeonInstance();
        Object activeDungeon = new FakeDungeonInstance();
        Set<Object> retainingSet = new HashSet<>(Set.of(endedDungeon, activeDungeon));

        assertTrue(EliteMobsInstanceLeakGuard.releaseDestroyedDungeonInstance(
                endedDungeon, FakeDungeonInstance.class, retainingSet));
        assertFalse(retainingSet.contains(endedDungeon));
        assertTrue(retainingSet.contains(activeDungeon));
    }

    @Test
    void doesNotRemoveNonDungeonInstances() {
        Object nonDungeonInstance = new Object();
        Set<Object> retainingSet = new HashSet<>(Set.of(nonDungeonInstance));

        assertFalse(EliteMobsInstanceLeakGuard.releaseDestroyedDungeonInstance(
                nonDungeonInstance, FakeDungeonInstance.class, retainingSet));
        assertTrue(retainingSet.contains(nonDungeonInstance));
    }

    private static final class FakeDungeonInstance {
    }
}
