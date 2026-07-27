package com.trinityforge.progression.infrastructure;

import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachedProgressionRepositoryTest {

    @Test
    void configuredTtlExpiresCachedProgression() {
        UUID playerId = UUID.randomUUID();
        ProgressionRepository delegate = mock(ProgressionRepository.class);
        PlayerProgression first = PlayerProgression.empty(playerId).withPoints(1, 0);
        PlayerProgression second = PlayerProgression.empty(playerId).withPoints(2, 0);
        when(delegate.load(playerId))
                .thenReturn(LoadResult.found(first))
                .thenReturn(LoadResult.found(second));
        AtomicLong now = new AtomicLong(1_000L);
        CachedProgressionRepository repository =
                new CachedProgressionRepository(delegate, () -> 100L, now::get);

        assertEquals(1, repository.load(playerId).orElseThrow().availablePoints());
        now.set(1_099L);
        assertEquals(1, repository.load(playerId).orElseThrow().availablePoints());
        now.set(1_100L);
        assertEquals(2, repository.load(playerId).orElseThrow().availablePoints());

        verify(delegate, times(2)).load(playerId);
    }
}
