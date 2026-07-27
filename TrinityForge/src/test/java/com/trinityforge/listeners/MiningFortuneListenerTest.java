package com.trinityforge.listeners;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningFortuneListenerTest {

    @Test
    void eachTfFortuneLevelAddsThirtyPercentExpectedExtra() {
        assertEquals(0.0, MiningFortuneListener.expectedExtraRate(0, 0, 1.0), 0.0);
        assertEquals(0.3, MiningFortuneListener.expectedExtraRate(1, 0, 1.0), 1e-9);
        assertEquals(0.9, MiningFortuneListener.expectedExtraRate(1, 2, 1.0), 1e-9);
    }
}
