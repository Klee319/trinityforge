package com.trinityforge.mobs;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Objects;

/**
 * One EntityType's vanilla mob-type definition (config: {@code combat/mob-types.yml}).
 *
 * <p>Effective stats use {@code base + coefficient * effectiveLevel}.
 */
public record MobTypeDefinition(EntityType entityType, int level, double coordinateCoefficient,
                                 Double maxHealth,
                                 DefenseStats physical, DefenseStats magical,
                                 AttackStats attack,
                                 MobLevelCoefficients levelCoefficients,
                                 List<MobDropEntry> drops) {

    public MobTypeDefinition {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(physical, "physical");
        Objects.requireNonNull(magical, "magical");
        Objects.requireNonNull(attack, "attack");
        Objects.requireNonNull(levelCoefficients, "levelCoefficients");
        Objects.requireNonNull(drops, "drops");
        if (level < 0) {
            throw new IllegalArgumentException("level must be >= 0: " + level);
        }
        if (!Double.isFinite(coordinateCoefficient)) {
            coordinateCoefficient = 0.0;
        }
        if (maxHealth != null && (!Double.isFinite(maxHealth) || maxHealth <= 0.0)) {
            throw new IllegalArgumentException("max-health must be > 0 when set: " + maxHealth);
        }
        drops = List.copyOf(drops);
    }
}
