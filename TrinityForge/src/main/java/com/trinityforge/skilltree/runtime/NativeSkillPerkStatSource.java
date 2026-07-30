package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link SkillPerkStatSource} backed by TrinityForge's native {@link ProgressionRepository}.
 *
 * <p>Returns the effective set of unlocked perk IDs from the {@code player_perk_states} SQLite
 * table. The effective set is exactly what {@link ProgressionRepository#loadPerkIds} returns
 * (perks explicitly unlocked and not revoked) — no fake-unlock or permanently-locked categories
 * exist in the native layer.
 *
 * <p>This is the Valhalla-independent replacement for {@link ValhallaSkillPerkStatSource}.
 * Phase 3 wiring will swap the active source in the plugin entry point.
 */
public final class NativeSkillPerkStatSource implements SkillPerkStatSource {

    private static final Logger LOG = Logger.getLogger(NativeSkillPerkStatSource.class.getName());

    private final ProgressionRepository repository;

    public NativeSkillPerkStatSource(ProgressionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Set<String> unlockedPerkIds(UUID playerId) {
        LoadResult<Set<String>> result = loadUnlockedPerkIds(playerId);
        if (result.isFailed()) {
            return Set.of();
        }
        return result.orElseGet(Set::of);
    }

    @Override
    public LoadResult<Set<String>> loadUnlockedPerkIds(UUID playerId) {
        if (playerId == null) return LoadResult.found(Set.of());
        LoadResult<Set<String>> result = repository.loadPerkIds(playerId);
        if (result.isFailed()) {
            LOG.log(Level.WARNING, "[progression] Failed to load perk stats for " + playerId,
                    result.error());
        }
        return result;
    }
}
