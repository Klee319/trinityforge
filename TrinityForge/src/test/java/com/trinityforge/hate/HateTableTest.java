package com.trinityforge.hate;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the leak fixes for gap C5: eviction on death/removal/quit/unload, the caps that bound
 * growth, and config-driven decay/TTL reclamation. Pure Java - no Bukkit server needed.
 */
class HateTableTest {

    private static final double EPS = 1e-9;
    private static final long T0 = 1_000_000L;

    private static HateSettings settings(int maxMobs, int maxAttackers, boolean decay,
                                         double decayPerSecond, long ttlMillis) {
        return new HateSettings(maxMobs, maxAttackers, decay, decayPerSecond, ttlMillis, 1.0);
    }

    @Test
    void addThreatAccumulatesPerAttacker() {
        HateTable table = new HateTable(HateSettings.defaults());
        UUID mob = UUID.randomUUID();
        UUID a = UUID.randomUUID();

        table.addThreat(mob, a, null, 5.0, T0);
        table.addThreat(mob, a, null, 3.0, T0 + 10);

        assertEquals(8.0, table.threatOf(mob, a), EPS);
        assertEquals(1, table.trackedMobCount());
    }

    @Test
    void ignoresNonPositiveOrNonFiniteAmounts() {
        HateTable table = new HateTable(HateSettings.defaults());
        UUID mob = UUID.randomUUID();
        UUID a = UUID.randomUUID();

        table.addThreat(mob, a, null, 0.0, T0);
        table.addThreat(mob, a, null, -4.0, T0);
        table.addThreat(mob, a, null, Double.NaN, T0);

        assertEquals(0, table.trackedMobCount());
    }

    @Test
    void topAttackerReturnsHighestThreat() {
        HateTable table = new HateTable(HateSettings.defaults());
        UUID mob = UUID.randomUUID();
        UUID weak = UUID.randomUUID();
        UUID strong = UUID.randomUUID();

        table.addThreat(mob, weak, null, 2.0, T0);
        table.addThreat(mob, strong, null, 9.0, T0);

        assertTrue(table.topAttacker(mob).isPresent());
        assertEquals(strong, table.topAttacker(mob).orElseThrow());
        assertTrue(table.topAttacker(UUID.randomUUID()).isEmpty());
    }

    @Test
    void topAttackerTieBreaksOnOlderLastAddMillisThenOnSmallerUuid() {
        HateTable table = new HateTable(HateSettings.defaults());
        UUID mob = UUID.randomUUID();
        // established: added earlier and never touched again -> older lastAddMillis
        UUID established = UUID.fromString("00000000-0000-0000-0000-000000000002");
        // newcomer: same final threat, but its add happened later -> newer lastAddMillis
        UUID newcomer = UUID.fromString("00000000-0000-0000-0000-000000000001");

        table.addThreat(mob, established, null, 5.0, T0);
        table.addThreat(mob, newcomer, null, 5.0, T0 + 1000); // same threat, added later

        // Tie on threat -> older lastAddMillis (established) wins, even though its UUID sorts higher.
        assertEquals(established, table.topAttacker(mob).orElseThrow());

        HateTable sameInstantTable = new HateTable(HateSettings.defaults());
        UUID smallerUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID largerUuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        sameInstantTable.addThreat(mob, largerUuid, null, 5.0, T0);
        sameInstantTable.addThreat(mob, smallerUuid, null, 5.0, T0);

        // Tie on threat and lastAddMillis -> smaller UUID wins.
        assertEquals(smallerUuid, sameInstantTable.topAttacker(mob).orElseThrow());
    }

    @Test
    void removeMobEvictsOnDeath() {
        HateTable table = new HateTable(HateSettings.defaults());
        UUID mob = UUID.randomUUID();
        table.addThreat(mob, UUID.randomUUID(), null, 5.0, T0);

        assertTrue(table.removeMob(mob));
        assertEquals(0, table.trackedMobCount());
        assertFalse(table.removeMob(mob));
    }

    @Test
    void removeAttackerClearsContributionsAcrossMobsAndDropsEmptyMobs() {
        HateTable table = new HateTable(HateSettings.defaults());
        UUID mob1 = UUID.randomUUID();
        UUID mob2 = UUID.randomUUID();
        UUID quitting = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        table.addThreat(mob1, quitting, null, 5.0, T0);
        table.addThreat(mob1, other, null, 3.0, T0);
        table.addThreat(mob2, quitting, null, 4.0, T0); // mob2 only has the quitting player

        int affected = table.removeAttacker(quitting);

        assertEquals(2, affected);
        assertEquals(0.0, table.threatOf(mob1, quitting), EPS);
        assertEquals(3.0, table.threatOf(mob1, other), EPS); // mob1 survives via other attacker
        assertEquals(1, table.trackedMobCount());            // mob2 emptied -> evicted
    }

    @Test
    void removeMobsInWorldEvictsOnlyThatWorld() {
        HateTable table = new HateTable(HateSettings.defaults());
        UUID worldA = UUID.randomUUID();
        UUID worldB = UUID.randomUUID();
        UUID mobA = UUID.randomUUID();
        UUID mobB = UUID.randomUUID();

        table.addThreat(mobA, UUID.randomUUID(), worldA, 5.0, T0);
        table.addThreat(mobB, UUID.randomUUID(), worldB, 5.0, T0);

        assertEquals(1, table.removeMobsInWorld(worldA));
        assertEquals(1, table.trackedMobCount());
        assertEquals(0.0, table.threatOf(mobA, UUID.randomUUID()), EPS);
    }

    @Test
    void addThreatAppliesPendingDecayToExistingThreatBeforeAdding() {
        // 50% decay/sec enabled. A busy attacker hitting faster than the sweep cadence must not
        // get a "free" accumulation window: decay owed since the last touch applies first.
        HateTable table = new HateTable(settings(100, 100, true, 0.5, 0));
        UUID mob = UUID.randomUUID();
        UUID a = UUID.randomUUID();

        table.addThreat(mob, a, null, 100.0, T0);
        table.addThreat(mob, a, null, 10.0, T0 + 1000); // 1s later: 100 decays to 50, then +10

        assertEquals(60.0, table.threatOf(mob, a), 1e-6);
    }

    @Test
    void addThreatWithDecayDisabledNeverAppliesDecayBetweenAdds() {
        HateTable table = new HateTable(settings(100, 100, false, 0.5, 0));
        UUID mob = UUID.randomUUID();
        UUID a = UUID.randomUUID();

        table.addThreat(mob, a, null, 100.0, T0);
        table.addThreat(mob, a, null, 10.0, T0 + 1000);

        assertEquals(110.0, table.threatOf(mob, a), EPS);
    }

    @Test
    void perMobCapFullRejectsNewAttackerAtOrBelowLowestThreat() {
        HateTable table = new HateTable(settings(100, 2, false, 0.0, 0));
        UUID mob = UUID.randomUUID();
        UUID low = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        UUID rejectedTie = UUID.randomUUID();
        UUID rejectedBelow = UUID.randomUUID();

        table.addThreat(mob, low, null, 3.0, T0);
        table.addThreat(mob, mid, null, 5.0, T0);   // cap (2) now full

        table.addThreat(mob, rejectedTie, null, 3.0, T0 + 1);   // == lowest (3.0) -> rejected
        table.addThreat(mob, rejectedBelow, null, 1.0, T0 + 1); // < lowest (3.0) -> rejected

        assertEquals(2, table.attackerCount(mob));
        assertEquals(0.0, table.threatOf(mob, rejectedTie), EPS);
        assertEquals(0.0, table.threatOf(mob, rejectedBelow), EPS);
        assertEquals(3.0, table.threatOf(mob, low), EPS); // incumbent untouched, not evicted
        assertEquals(5.0, table.threatOf(mob, mid), EPS);
    }

    @Test
    void perMobCapFullAdmitsNewAttackerThatExceedsLowestThreat() {
        HateTable table = new HateTable(settings(100, 2, false, 0.0, 0));
        UUID mob = UUID.randomUUID();
        UUID low = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        UUID admitted = UUID.randomUUID();

        table.addThreat(mob, low, null, 3.0, T0);
        table.addThreat(mob, mid, null, 5.0, T0);

        table.addThreat(mob, admitted, null, 3.01, T0 + 1); // strictly exceeds lowest -> admitted

        assertEquals(2, table.attackerCount(mob));
        assertEquals(0.0, table.threatOf(mob, low)); // evicted
        assertEquals(5.0, table.threatOf(mob, mid), EPS);
        assertEquals(3.01, table.threatOf(mob, admitted), EPS);
    }

    @Test
    void perMobAttackerCapBoundsGrowthEvictingLowestThreat() {
        HateTable table = new HateTable(settings(100, 2, false, 0.0, 0));
        UUID mob = UUID.randomUUID();
        UUID low = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        UUID high = UUID.randomUUID();

        table.addThreat(mob, low, null, 1.0, T0);
        table.addThreat(mob, mid, null, 5.0, T0);
        table.addThreat(mob, high, null, 9.0, T0); // exceeds cap of 2 -> evict lowest (low)

        assertEquals(2, table.attackerCount(mob));
        assertEquals(0.0, table.threatOf(mob, low), EPS);
        assertEquals(5.0, table.threatOf(mob, mid), EPS);
        assertEquals(9.0, table.threatOf(mob, high), EPS);
    }

    @Test
    void globalMobCapBoundsGrowthEvictingLeastRecentlyTouched() {
        HateTable table = new HateTable(settings(2, 100, false, 0.0, 0));
        UUID oldMob = UUID.randomUUID();
        UUID midMob = UUID.randomUUID();
        UUID newMob = UUID.randomUUID();

        table.addThreat(oldMob, UUID.randomUUID(), null, 5.0, T0);
        table.addThreat(midMob, UUID.randomUUID(), null, 5.0, T0 + 100);
        table.addThreat(newMob, UUID.randomUUID(), null, 5.0, T0 + 200); // evicts oldMob (LRU)

        assertEquals(2, table.trackedMobCount());
        assertEquals(0, table.attackerCount(oldMob));
        assertTrue(table.attackerCount(midMob) > 0);
        assertTrue(table.attackerCount(newMob) > 0);
    }

    @Test
    void sweepDecayReducesThreatOverTimeWhenEnabled() {
        // 50% decay per second; 2 seconds elapsed -> 0.25 of original remains.
        HateTable table = new HateTable(settings(100, 100, true, 0.5, 0));
        UUID mob = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        table.addThreat(mob, a, null, 100.0, T0);

        table.sweep(T0 + 2000);

        assertEquals(25.0, table.threatOf(mob, a), 1e-6);
    }

    @Test
    void decayDisabledLeavesThreatUntouched() {
        HateTable table = new HateTable(settings(100, 100, false, 0.5, 0));
        UUID mob = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        table.addThreat(mob, a, null, 100.0, T0);

        table.sweep(T0 + 10_000);

        assertEquals(100.0, table.threatOf(mob, a), EPS);
    }

    @Test
    void sweepDropsDecayedToZeroAndEmptyMobs() {
        // Strong decay drives the lone entry below the floor; its mob is then reclaimed.
        HateTable table = new HateTable(settings(100, 100, true, 0.99, 0));
        UUID mob = UUID.randomUUID();
        table.addThreat(mob, UUID.randomUUID(), null, 1.0, T0);

        int removed = table.sweep(T0 + 5000);

        assertEquals(1, removed);
        assertEquals(0, table.trackedMobCount());
    }

    @Test
    void sweepEvictsTtlExpiredEntries() {
        HateTable table = new HateTable(settings(100, 100, false, 0.0, 1000L)); // 1s TTL
        UUID mob = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        table.addThreat(mob, a, null, 50.0, T0);

        table.sweep(T0 + 500);                 // within TTL -> kept
        assertEquals(50.0, table.threatOf(mob, a), EPS);

        int removed = table.sweep(T0 + 1500);  // past TTL -> evicted
        assertEquals(1, removed);
        assertEquals(0, table.trackedMobCount());
    }

    @Test
    void ttlDisabledKeepsStaleEntries() {
        HateTable table = new HateTable(settings(100, 100, false, 0.0, 0)); // TTL off
        UUID mob = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        table.addThreat(mob, a, null, 50.0, T0);

        table.sweep(T0 + 10_000_000L);

        assertEquals(50.0, table.threatOf(mob, a), EPS);
    }

    @Test
    void applySettingsSwapsCapsForSubsequentAdds() {
        HateTable table = new HateTable(settings(100, 100, false, 0.0, 0));
        UUID mob = UUID.randomUUID();
        table.addThreat(mob, UUID.randomUUID(), null, 1.0, T0);
        table.addThreat(mob, UUID.randomUUID(), null, 2.0, T0);

        table.applySettings(settings(100, 1, false, 0.0, 0)); // tighten to 1 attacker
        table.addThreat(mob, UUID.randomUUID(), null, 9.0, T0 + 1);

        assertEquals(1, table.attackerCount(mob));
    }

    @Test
    void clearDropsEverything() {
        HateTable table = new HateTable(HateSettings.defaults());
        table.addThreat(UUID.randomUUID(), UUID.randomUUID(), null, 5.0, T0);
        table.addThreat(UUID.randomUUID(), UUID.randomUUID(), null, 5.0, T0);

        table.clear();

        assertEquals(0, table.trackedMobCount());
    }
}
