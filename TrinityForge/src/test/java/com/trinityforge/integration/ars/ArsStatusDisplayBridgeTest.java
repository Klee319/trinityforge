package com.trinityforge.integration.ars;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArsStatusDisplayBridgeTest {

    @Test
    void projectsFinalMaxManaInsteadOfTheRawBonus() {
        Map<String, Double> result = ArsStatusDisplayBridge.project(
                Map.of("mana_bonus", 40.0), 140, 0);

        assertEquals(140.0, result.get("mana_bonus"));
    }

    @Test
    void combinesThreadAndTfCostReductionAndClampsAtOne() {
        Map<String, Double> result = ArsStatusDisplayBridge.project(
                Map.of("mana_cost_reduction_percent", 0.35), 100, 80);

        assertEquals(1.0, result.get("mana_cost_reduction_percent"));
    }
}
