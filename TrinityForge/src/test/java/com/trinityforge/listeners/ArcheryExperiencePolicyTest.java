package com.trinityforge.listeners;

import com.trinityforge.progression.catalog.SkillCatalogEntry;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArcheryExperiencePolicyTest {

    private static SkillCatalogEntry configured() {
        return new SkillCatalogEntry("ARCHERY", 100, "1", ignored -> 1L, Map.of(), Map.ofEntries(
                Map.entry("archery.bow_base", 30.0),
                Map.entry("archery.crossbow_base", 40.0),
                Map.entry("archery.damage_bonus", 0.1),
                Map.entry("archery.distance_base", 1.0),
                Map.entry("archery.distance_per_10", 0.75),
                Map.entry("archery.distance_limit", 100.0),
                Map.entry("archery.infinity_multiplier", 0.7),
                Map.entry("archery.spawner_multiplier", 0.7),
                Map.entry("archery.max_health_limitation", 1.0),
                Map.entry("archery.pvp_multiplier", 0.1),
                Map.entry("archery.entity.ZOMBIE", 1.5)));
    }

    @Test
    void reproducesValhallaBowDamageAndDistanceFormula() {
        double exp = ArcheryExperiencePolicy.calculate(configured(), Material.BOW,
                10.0, 20.0, 20.0, "ZOMBIE", false, false, false);

        // base30 * distance(1 + ceil(20/10)*0.75=2.5) * damage(1 + 10*0.1=2) * zombie1.5
        assertEquals(225.0, exp, 1e-9);
    }

    @Test
    void appliesCrossbowInfinitySpawnerPvpAndHealthLimitSettings() {
        double exp = ArcheryExperiencePolicy.calculate(configured(), Material.CROSSBOW,
                100.0, 0.0, 20.0, "UNKNOWN", true, true, true);

        // crossbow40 * damage capped to maxHealth: (1+20*0.1)=3 * infinity .7 * spawner .7 * pvp .1
        assertEquals(5.88, exp, 1e-9);
    }

    @Test
    void distanceIsCappedByEditableLimit() {
        double atLimit = ArcheryExperiencePolicy.calculate(configured(), Material.BOW,
                0.0, 100.0, 20.0, "UNKNOWN", false, false, false);
        double beyondLimit = ArcheryExperiencePolicy.calculate(configured(), Material.BOW,
                0.0, 1000.0, 20.0, "UNKNOWN", false, false, false);
        assertEquals(atLimit, beyondLimit, 1e-9);
    }

    @Test
    void distanceUsesValhallaTenBlockCeilingBuckets() {
        double d1 = ArcheryExperiencePolicy.calculate(configured(), Material.BOW,
                0.0, 1.0, 20.0, "UNKNOWN", false, false, false);
        double d9 = ArcheryExperiencePolicy.calculate(configured(), Material.BOW,
                0.0, 9.0, 20.0, "UNKNOWN", false, false, false);
        double d10 = ArcheryExperiencePolicy.calculate(configured(), Material.BOW,
                0.0, 10.0, 20.0, "UNKNOWN", false, false, false);
        double d50 = ArcheryExperiencePolicy.calculate(configured(), Material.BOW,
                0.0, 50.0, 20.0, "UNKNOWN", false, false, false);
        double d100 = ArcheryExperiencePolicy.calculate(configured(), Material.BOW,
                0.0, 100.0, 20.0, "UNKNOWN", false, false, false);
        double d101 = ArcheryExperiencePolicy.calculate(configured(), Material.BOW,
                0.0, 101.0, 20.0, "UNKNOWN", false, false, false);

        assertEquals(52.5, d1, 1e-9);
        assertEquals(d1, d9, 1e-9);
        assertEquals(d1, d10, 1e-9);
        assertEquals(142.5, d50, 1e-9);
        assertEquals(255.0, d100, 1e-9);
        assertEquals(d100, d101, 1e-9);
    }
}
