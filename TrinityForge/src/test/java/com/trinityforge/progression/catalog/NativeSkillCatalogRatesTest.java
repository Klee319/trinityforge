package com.trinityforge.progression.catalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeSkillCatalogRatesTest {

    @Test
    void classpathCatalogExposesWiredProducerRates() {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        // 2026-07-25 PRG-08: SP供給不足の是正で exp_gain を 100 -> 240 へ引き上げた。
        assertEquals(240.0, catalog.get("POWER").rate("power.exp_per_skill_level", -1), 0.001);
        assertEquals(0.5, catalog.get("ENCHANTING").rate("enchant.level_cost_multiplier", -1), 0.001);
        assertEquals(-1.0, catalog.get("SMITHING").rate("smithing.tool_stack", -1), 0.001,
                "durability-based legacy smithing EXP must not be loaded");
        assertEquals(10.0, catalog.get("LIGHT_ARMOR").rate("armor.exp_per_damage_piece", -1), 0.001);
        assertEquals(0.05,
                catalog.get("LIGHT_ARMOR").rate("armor.exp_armor_point_multiplier", -1), 0.001);
        assertEquals(0.1, catalog.get("LIGHT_ARMOR").rate("armor.pvp_multiplier", -1), 0.001);
        assertEquals(1.0,
                catalog.get("LIGHT_ARMOR").rate("armor.pvp_multiplier_exponent", -1), 0.001);
        assertEquals(2.0,
                catalog.get("HEAVY_ARMOR").rate("armor.pvp_multiplier_exponent", -1), 0.001);
        assertEquals(1.0,
                catalog.get("HEAVY_ARMOR").rate("armor.location_diminishing_enabled", -1), 0.001);
        assertEquals(150.0, catalog.get("ALCHEMY").rate("alchemy.brew", -1), 0.001);
        assertEquals(20.0, catalog.get("FISHING").rate("fishing.catch", -1), 0.001);
        assertEquals(30.0, catalog.get("ARCHERY").rate("archery.bow_base", -1), 0.001);
        assertEquals(40.0, catalog.get("ARCHERY").rate("archery.crossbow_base", -1), 0.001);
        assertEquals(0.1, catalog.get("ARCHERY").rate("archery.damage_bonus", -1), 0.001);
        assertEquals(0.75, catalog.get("ARCHERY").rate("archery.distance_per_10", -1), 0.001);
        assertEquals(0.7, catalog.get("ARCHERY").rate("archery.infinity_multiplier", -1), 0.001);
        assertEquals(0.1, catalog.get("ARCHERY").rate("archery.pvp_multiplier", -1), 0.001);
        assertTrue(catalog.get("MINING").expFor("mining_break", "DIAMOND_ORE") > 0.0);
    }
}
