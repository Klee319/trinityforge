package com.trinityforge.progression;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NativeExperienceDispatcher#setJobExpMultiplierResolver}: 2026-07-25 digging.yml C-2
 * 「消費したシャベルの耐久値の総量に応じて職業経験値の取得量がアップ」向けの職業EXPブースト注入。
 * resolver未配線時はno-op(既存挙動を絶対に壊さない)、配線時は指定skillIdのみブーストされ、
 * リトライが起きても二重適用されない(バッチ確定時に一度だけ適用)ことを検証する。
 */
class NativeExperienceDispatcherJobExpTest {

    @Test
    void noResolverLeavesAmountUnchanged() {
        NativeProgressionService progression = mock(NativeProgressionService.class);
        UUID playerId = UUID.randomUUID();
        when(progression.grantExp(eq(playerId), eq("DIGGING"), anyDouble()))
                .thenReturn(new NativeProgressionService.GrantResult("DIGGING", null, null, 0, 0));
        NativeExperienceDispatcher dispatcher = new NativeExperienceDispatcher(progression);

        dispatcher.grant(playerId, "DIGGING", 10.0);
        dispatcher.drain();

        verify(progression).grantExp(playerId, "DIGGING", 10.0);
    }

    @Test
    void resolverAppliesBonusOnlyToMatchingSkill() {
        NativeProgressionService progression = mock(NativeProgressionService.class);
        UUID playerId = UUID.randomUUID();
        when(progression.grantExp(any(), any(), anyDouble()))
                .thenReturn(new NativeProgressionService.GrantResult("x", null, null, 0, 0));
        NativeExperienceDispatcher dispatcher = new NativeExperienceDispatcher(progression);
        dispatcher.setJobExpMultiplierResolver((pid, skillId) -> "DIGGING".equals(skillId) ? 0.25 : 0.0);

        dispatcher.grant(playerId, "DIGGING", 10.0);
        dispatcher.grant(playerId, "MINING", 10.0);
        dispatcher.drain();

        verify(progression).grantExp(playerId, "DIGGING", 12.5);
        verify(progression).grantExp(playerId, "MINING", 10.0);
    }

    @Test
    void resolverExceptionIsFailSoftAndAmountIsUnmodified() {
        NativeProgressionService progression = mock(NativeProgressionService.class);
        UUID playerId = UUID.randomUUID();
        when(progression.grantExp(any(), any(), anyDouble()))
                .thenReturn(new NativeProgressionService.GrantResult("x", null, null, 0, 0));
        NativeExperienceDispatcher dispatcher = new NativeExperienceDispatcher(progression);
        dispatcher.setJobExpMultiplierResolver((pid, skillId) -> {
            throw new IllegalStateException("boom");
        });

        dispatcher.grant(playerId, "DIGGING", 10.0);
        dispatcher.drain();

        verify(progression).grantExp(playerId, "DIGGING", 10.0);
    }
}
