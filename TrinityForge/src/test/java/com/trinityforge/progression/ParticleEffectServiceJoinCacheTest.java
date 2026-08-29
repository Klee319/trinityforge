package com.trinityforge.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleEffectServiceJoinCacheTest {

    @Test
    void emptyIsNotCachedDuringTheHuskSyncGraceWindow() {
        assertFalse(ParticleEffectService.shouldCacheEmpty(10, 0, 40L),
                "参加直後の空読みをキャッシュするとサーバ移動後ずっと出ない");
    }

    @Test
    void emptyMayBeCachedAfterTheGraceWindow() {
        assertTrue(ParticleEffectService.shouldCacheEmpty(40, 0, 40L));
    }

    @Test
    void unknownJoinTickCachesEmpty() {
        assertTrue(ParticleEffectService.shouldCacheEmpty(0, null, 40L));
    }

    @Test
    void helixPhaseAdvancesOneTurnPerSecond() {
        assertEquals(0.0, ParticleEffectService.helixPhaseTurns(0), 1e-9);
        assertEquals(1.0, ParticleEffectService.helixPhaseTurns(20), 1e-9);
    }
}
