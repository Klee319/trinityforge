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
        assertTrue(catalog.get("MINING").expFor("mining_break", "DIAMOND_ORE") > 0.0);
    }

    /**
     * N5(2026-07-31): 弓術EXPを討伐時ベース({@code stats/skill-exp.yml} の {@code combat.kill-exp})へ
     * 統一したので、{@code archery.*} の per-hit レート変換は削除済み。カタログが値を返さないこと
     * (=「editor から編集できるのに効かないキー」を作っていないこと)を固定する。
     *
     * <p>ここで {@code -1} が返るのは「そのレートが未定義」の意味(既定値をそのまま返す)。
     */
    @Test
    void perHitArcheryRatesAreNoLongerTranslated() {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        for (String removed : new String[] {
            "archery.bow_base", "archery.crossbow_base", "archery.damage_bonus",
            "archery.distance_base", "archery.distance_per_10", "archery.distance_limit",
            "archery.infinity_multiplier", "archery.spawner_multiplier",
            "archery.pvp_multiplier", "archery.max_health_limitation",
            "archery.entity.ZOMBIE"
        }) {
            assertEquals(-1.0, catalog.get("ARCHERY").rate(removed, -1), 0.001,
                    removed + " は削除済みの per-hit 係数。カタログへ復活させてはならない");
        }
    }

    /** 弓術に残るのはレベル曲線だけ(獲得量ではなく必要EXP量)。曲線は生きていること。 */
    @Test
    void archeryKeepsItsLevelCurve() {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        assertEquals(100, catalog.get("ARCHERY").maxLevel());
        assertTrue(catalog.get("ARCHERY").curve().expRequiredAt(10) > 1L,
                "弓術のレベル曲線が読めていない(archery_progression.yml の exp_level_curve)");
    }
}
