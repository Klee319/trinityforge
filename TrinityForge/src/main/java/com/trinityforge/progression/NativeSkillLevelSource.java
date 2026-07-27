package com.trinityforge.progression;

import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link SkillLevelSource} backed by TrinityForge's native {@link ProgressionRepository}.
 *
 * <p>Returns the stored {@link SkillProgress#level()} for each skill the player has progressed in.
 * Skills that have never been saved are absent from the returned map; the consumer
 * ({@link com.trinityforge.progression.CombatLevelModel}) treats absent skills as level 0.
 *
 * <p>This is the Valhalla-independent replacement for {@link ValhallaSkillLevelSource}.
 * Phase 3 wiring will swap the active source in the plugin entry point.
 */
public final class NativeSkillLevelSource implements SkillLevelSource {

    private static final Logger LOG = Logger.getLogger(NativeSkillLevelSource.class.getName());

    private final ProgressionRepository repository;

    public NativeSkillLevelSource(ProgressionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Map<String, Integer> levelsOf(UUID playerId) {
        if (playerId == null) return Map.of();
        LoadResult<com.trinityforge.progression.core.PlayerProgression> result =
                repository.load(playerId);
        if (result.isFailed()) {
            LOG.log(Level.WARNING, "[progression] Failed to load skill levels for " + playerId,
                    result.error());
            return Map.of();
        }
        if (result.isMissing()) {
            return Map.of();
        }
        Map<String, Integer> levels = new HashMap<>();
        for (Map.Entry<String, SkillProgress> entry : result.orElseThrow().skills().entrySet()) {
            levels.put(entry.getKey(), entry.getValue().level());
        }
        return Map.copyOf(levels);
    }
}
