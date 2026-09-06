package com.trinityforge.combat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelegraphBudgetTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID OTHER_PLAYER = UUID.randomUUID();
    private static final UUID CASTER = UUID.randomUUID();
    private static final UUID OTHER_CASTER = UUID.randomUUID();

    @Test
    @DisplayName("3本目の通常予告は canReserve=false / tryReserve は空")
    void thirdNormalReservationRejected() {
        TelegraphBudget budget = new TelegraphBudget(() -> 0L);
        assertTrue(budget.tryReserve(PLAYER, CASTER, "a", false, 1000L).isPresent());
        assertTrue(budget.tryReserve(PLAYER, CASTER, "b", false, 1000L).isPresent());

        assertFalse(budget.canReserve(PLAYER, false));
        assertTrue(budget.tryReserve(PLAYER, CASTER, "c", false, 1000L).isEmpty());
    }

    @Test
    @DisplayName("致命1本が走っている間、2本目の致命は不可、通常1本は可")
    void secondLethalRejectedWhileFirstActive() {
        TelegraphBudget budget = new TelegraphBudget(() -> 0L);
        assertTrue(budget.tryReserve(PLAYER, CASTER, "lethal-1", true, 1000L).isPresent());

        assertFalse(budget.canReserve(PLAYER, true));
        assertTrue(budget.tryReserve(PLAYER, CASTER, "lethal-2", true, 1000L).isEmpty());

        assertTrue(budget.canReserve(PLAYER, false));
        assertTrue(budget.tryReserve(PLAYER, CASTER, "normal-1", false, 1000L).isPresent());
    }

    @Test
    @DisplayName("通常2本が走っていると致命も不可（合計上限）")
    void lethalRejectedWhenTotalLimitReachedByNormals() {
        TelegraphBudget budget = new TelegraphBudget(() -> 0L);
        assertTrue(budget.tryReserve(PLAYER, CASTER, "normal-1", false, 1000L).isPresent());
        assertTrue(budget.tryReserve(PLAYER, CASTER, "normal-2", false, 1000L).isPresent());

        assertFalse(budget.canReserve(PLAYER, true));
        assertTrue(budget.tryReserve(PLAYER, CASTER, "lethal-1", true, 1000L).isEmpty());
    }

    @Test
    @DisplayName("release 後に再予約できる")
    void reservationFreedAfterRelease() {
        TelegraphBudget budget = new TelegraphBudget(() -> 0L);
        Optional<TelegraphBudget.Reservation> first =
                budget.tryReserve(PLAYER, CASTER, "lethal-1", true, 1000L);
        assertTrue(first.isPresent());
        assertTrue(budget.tryReserve(PLAYER, CASTER, "lethal-2", true, 1000L).isEmpty());

        budget.release(first.get());

        assertTrue(budget.canReserve(PLAYER, true));
        assertTrue(budget.tryReserve(PLAYER, CASTER, "lethal-2", true, 1000L).isPresent());
    }

    @Test
    @DisplayName("releaseCaster が複数プレイヤーにまたがる同一術者の予約を全部外す")
    void releaseCasterClearsAcrossPlayers() {
        TelegraphBudget budget = new TelegraphBudget(() -> 0L);
        budget.tryReserve(PLAYER, CASTER, "a", false, 1000L);
        budget.tryReserve(OTHER_PLAYER, CASTER, "b", false, 1000L);
        budget.tryReserve(OTHER_PLAYER, OTHER_CASTER, "c", false, 1000L);

        int removed = budget.releaseCaster(CASTER);

        assertEquals(2, removed);
        assertTrue(budget.active(PLAYER).isEmpty());
        assertEquals(1, budget.active(OTHER_PLAYER).size());
        assertEquals("c", budget.active(OTHER_PLAYER).get(0).abilityId());
    }

    @Test
    @DisplayName("releasePlayer はそのプレイヤーだけ外し、他プレイヤーは残る")
    void releasePlayerClearsOnlyThatPlayer() {
        TelegraphBudget budget = new TelegraphBudget(() -> 0L);
        budget.tryReserve(PLAYER, CASTER, "a", false, 1000L);
        budget.tryReserve(OTHER_PLAYER, CASTER, "b", false, 1000L);

        int removed = budget.releasePlayer(PLAYER);

        assertEquals(1, removed);
        assertTrue(budget.active(PLAYER).isEmpty());
        assertEquals(1, budget.active(OTHER_PLAYER).size());
    }

    @Test
    @DisplayName("期限切れ（resolveAt + 1000ms 超）は canReserve 時点で自動解放される")
    void expiredReservationsAutoReleaseOnCanReserve() {
        long[] now = {0L};
        TelegraphBudget budget = new TelegraphBudget(() -> now[0]);
        budget.tryReserve(PLAYER, CASTER, "a", true, 1000L);
        budget.tryReserve(PLAYER, CASTER, "b", false, 1000L);
        assertFalse(budget.canReserve(PLAYER, true));

        // resolveAt(1000) + grace(1000) = 2000。2001ms 時点では期限切れ。
        now[0] = 2001L;

        assertTrue(budget.canReserve(PLAYER, true));
        assertTrue(budget.active(PLAYER).isEmpty());
    }

    @Test
    @DisplayName("同じ Reservation を2回 release しても例外なし")
    void doubleReleaseIsNoOp() {
        TelegraphBudget budget = new TelegraphBudget(() -> 0L);
        Optional<TelegraphBudget.Reservation> reservation =
                budget.tryReserve(PLAYER, CASTER, "a", false, 1000L);
        assertTrue(reservation.isPresent());

        budget.release(reservation.get());
        assertDoesNotThrow(() -> budget.release(reservation.get()));

        assertTrue(budget.active(PLAYER).isEmpty());
    }
}
