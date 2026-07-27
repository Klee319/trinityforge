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
        assertEquals(0.01, catalog.get("SMITHING").rate("smithing.tool_stack", -1), 0.001);
        assertEquals(0.05, catalog.get("LIGHT_ARMOR").rate("armor.damage_exp_rate", -1), 0.001);
        assertEquals(25.0, catalog.get("ALCHEMY").rate("alchemy.brew", -1), 0.001);
        assertEquals(20.0, catalog.get("FISHING").rate("fishing.catch", -1), 0.001);
        assertTrue(catalog.get("MINING").expFor("mining_break", "DIAMOND_ORE") > 0.0);
    }
}
