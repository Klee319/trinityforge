package com.trinityforge.listeners;

import com.trinityforge.progression.catalog.SkillCatalogEntry;
import org.bukkit.Material;

/**
 * Data-only implementation of ValhallaMMO's archery action EXP formula.
 *
 * <p>All coefficients come from {@code skills/base/archery_progression.yml}; this class contains no
 * gameplay balance constants. The existing TF location diminishing system remains the anti-farm layer.
 */
final class ArcheryExperiencePolicy {

    private ArcheryExperiencePolicy() {
    }

    static double calculate(SkillCatalogEntry config, Material weapon, double finalDamage,
                            double distance, double targetMaxHealth, String entityType,
                            boolean infinity, boolean spawnerSpawned, boolean pvp) {
        if (config == null || (weapon != Material.BOW && weapon != Material.CROSSBOW)) return 0.0;
        double base = config.rate(weapon == Material.CROSSBOW
                ? "archery.crossbow_base" : "archery.bow_base", 0.0);
        double limit = Math.max(0.0, config.rate("archery.distance_limit", 0.0));
        double distanceSteps = Math.ceil(Math.min(Math.max(0.0, distance), limit) / 10.0);
        double distanceMultiplier = Math.max(0.0,
                config.rate("archery.distance_base", 1.0)
                        + config.rate("archery.distance_per_10", 0.0) * distanceSteps);

        boolean maxHealthLimit = config.rate("archery.max_health_limitation", 0.0) > 0.0;
        double damageForExp = Math.max(0.0, finalDamage);
        if (maxHealthLimit) {
            damageForExp = Math.min(damageForExp, Math.max(0.0, targetMaxHealth));
        }
        double damageMultiplier = Math.max(0.0,
                1.0 + config.rate("archery.damage_bonus", 0.0) * damageForExp);
        double entityMultiplier = Math.max(0.0,
                config.rate("archery.entity." + normalize(entityType), 1.0));
        double specialMultiplier = (infinity
                ? Math.max(0.0, config.rate("archery.infinity_multiplier", 1.0)) : 1.0)
                * (spawnerSpawned
                ? Math.max(0.0, config.rate("archery.spawner_multiplier", 1.0)) : 1.0)
                * (pvp ? Math.max(0.0, config.rate("archery.pvp_multiplier", 1.0)) : 1.0);
        return Math.max(0.0, base) * distanceMultiplier * damageMultiplier
                * entityMultiplier * specialMultiplier;
    }

    private static String normalize(String entityType) {
        return entityType == null ? "" : entityType.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
