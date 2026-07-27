package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure state transitions for a bleed DoT (Q3 = (c)). */
class BleedInstanceTest {

    private static final UUID ATTACKER = new UUID(1L, 2L);

    @Test
    void afterTickDecrementsUntilExpired() {
        BleedInstance b = new BleedInstance(ATTACKER, 2.0, 3);
        assertFalse(b.isExpired());
        b = b.afterTick();
        assertEquals(2, b.remainingTicks());
        b = b.afterTick().afterTick();
        assertTrue(b.isExpired());
        assertEquals(0, b.remainingTicks());
    }

    @Test
    void remainingTicksClampsAtZero() {
        BleedInstance expired = new BleedInstance(ATTACKER, 2.0, 0);
        assertTrue(expired.isExpired());
        assertEquals(0, expired.afterTick().remainingTicks()); // never negative
        assertEquals(0, new BleedInstance(ATTACKER, 2.0, -5).remainingTicks());
    }

    @Test
    void rejectsNegativeOrNonFiniteDamage() {
        assertThrows(IllegalArgumentException.class, () -> new BleedInstance(ATTACKER, -1.0, 3));
        assertThrows(IllegalArgumentException.class, () -> new BleedInstance(ATTACKER, Double.NaN, 3));
        assertThrows(NullPointerException.class, () -> new BleedInstance(null, 1.0, 3));
    }
}
