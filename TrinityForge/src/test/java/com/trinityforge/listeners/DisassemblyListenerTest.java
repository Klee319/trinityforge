package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.DisassemblyRule;
import com.trinityforge.stats.MaterialLists;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisassemblyListenerTest {

    @Test
    void materialListInputCountsEveryConfiguredEquivalentVanillaMaterial() {
        MaterialLists.update(Map.of("planks", java.util.Set.of(Material.OAK_PLANKS, Material.SPRUCE_PLANKS)), Map.of());

        assertTrue(DisassemblyListener.materialMatchesInput(Material.OAK_PLANKS, "list:planks"));
        assertTrue(DisassemblyListener.materialMatchesInput(Material.SPRUCE_PLANKS, "list:planks"));
        assertTrue(!DisassemblyListener.materialMatchesInput(Material.STICK, "list:planks"));
    }

    @Test
    void targetSeriesAcceptsTrailingWildcardAndHasNoImplicitFallback() throws ReflectiveOperationException {
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        Field field = CraftingFeaturesConfig.class.getDeclaredField("disassemblyItems");
        field.setAccessible(true);
        field.set(config, Map.of("bronze_*", List.of(new DisassemblyRule("IRON_INGOT", "COPPER_INGOT", 0.5))));

        assertEquals(1, config.disassemblyRulesFor("bronze_war_axe").size());
        assertTrue(config.disassemblyRulesFor("iron_war_axe").isEmpty(),
                "unconfigured targets must not receive a scrap or material fallback");
    }

    @Test
    void exactRuleWinsOverWildcardAndTheMostSpecificWildcardWins() throws ReflectiveOperationException {
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        Field field = CraftingFeaturesConfig.class.getDeclaredField("disassemblyItems");
        field.setAccessible(true);
        DisassemblyRule broad = new DisassemblyRule("IRON_INGOT", "COBBLESTONE", 1.0);
        DisassemblyRule specific = new DisassemblyRule("IRON_INGOT", "IRON_NUGGET", 1.0);
        DisassemblyRule exact = new DisassemblyRule("IRON_INGOT", "DIAMOND", 1.0);
        field.set(config, Map.of("weapon_*", List.of(broad), "weapon_iron_*", List.of(specific),
                "weapon_iron_sword", List.of(exact)));

        assertEquals("DIAMOND", config.disassemblyRulesFor("weapon_iron_sword").getFirst().output());
        assertEquals("IRON_NUGGET", config.disassemblyRulesFor("weapon_iron_axe").getFirst().output());
    }

    @Test
    void returnAmountUsesTheSpecifiedDoubleFloorAndRejectsUnsafeValues() {
        // floor(floor(8 × 3 × 25 / 100) × 1.5) = floor(6 × 1.5) = 9
        assertEquals(9, DisassemblyListener.returnAmount(8, 3, 25, 1.5));
        assertEquals(0, DisassemblyListener.returnAmount(2.0 / 3.0, 1, 100, 1.0),
                "one item from a three-output recipe must not refund two full ingredients");
        assertEquals(-1, DisassemblyListener.returnAmount(8, 3, 25, Double.POSITIVE_INFINITY));
        assertEquals(-1, DisassemblyListener.returnAmount(8, Integer.MAX_VALUE, Integer.MAX_VALUE, 1.0));
    }

    // ---- T3 (2026-07-25経済連携): disassembly_return_bonus 追加乗算オーバーロード ----
    // 5引数版はpackage-privateで、このテストクラスと同一パッケージのため直接呼べる。

    @Test
    void returnAmountWithBuffAppliesAdditionalMultiplierOnTopOfTheBaseCalculation() {
        // base = floor(floor(8 × 3 × 25 / 100) × 1.5) = 9 (same as the 4-arg case above).
        // buffed = floor(9 × (1 + 0.5)) = floor(13.5) = 13.
        assertEquals(13L, DisassemblyListener.returnAmount(8, 3, 25, 1.5, 0.5));
    }

    @Test
    void returnAmountWithBuffIgnoresNonPositiveOrNonFiniteBuffAndReturnsTheBaseValueUnchanged() {
        long base = DisassemblyListener.returnAmount(8, 3, 25, 1.5);
        assertEquals(base, DisassemblyListener.returnAmount(8, 3, 25, 1.5, 0.0));
        assertEquals(base, DisassemblyListener.returnAmount(8, 3, 25, 1.5, -0.5));
        assertEquals(base, DisassemblyListener.returnAmount(8, 3, 25, 1.5, Double.NaN));
    }

    @Test
    void returnAmountWithBuffKeepsTheSafetyValveWhenTheBaseCalculationAlreadyTripped() {
        // Base calc already trips the -1 safety valve (POSITIVE_INFINITY multiplier); a huge buff
        // must never resurrect a positive return amount out of an already-unsafe base.
        assertEquals(-1L, DisassemblyListener.returnAmount(8, 3, 25, Double.POSITIVE_INFINITY, 999.0));
    }

    @Test
    void returnAmountWithBuffKeepsTheSafetyValveWhenTheBuffedResultOverflows() {
        // A valid, moderate base amount pushed past Integer.MAX_VALUE by an extreme buff must still
        // trip the safety valve (-1), not silently overflow into a bogus positive long.
        assertEquals(-1L, DisassemblyListener.returnAmount(8, 3, 25, 1.5, (double) Integer.MAX_VALUE));
    }
}
