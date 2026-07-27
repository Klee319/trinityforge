package com.trinityforge.food;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FoodGimmickPolicy}: junk-food classification and junkfood-inversion saturation math. */
class FoodGimmickPolicyTest {

    private static final Set<Material> JUNK = Set.of(Material.ROTTEN_FLESH, Material.SPIDER_EYE);

    @Test
    void isJunkFoodTrueWhenMaterialInSet() {
        assertTrue(FoodGimmickPolicy.isJunkFood(Material.ROTTEN_FLESH, JUNK));
    }

    @Test
    void isJunkFoodFalseWhenMaterialNotInSet() {
        assertFalse(FoodGimmickPolicy.isJunkFood(Material.COOKED_BEEF, JUNK));
    }

    @Test
    void isJunkFoodNullSafe() {
        assertFalse(FoodGimmickPolicy.isJunkFood(null, JUNK));
        assertFalse(FoodGimmickPolicy.isJunkFood(Material.ROTTEN_FLESH, null));
    }

    @Test
    void junkFoodGetsPositiveBonusAdjustment() {
        assertEquals(2.0, FoodGimmickPolicy.inversionSaturationAdjustment(true, 2.0, 1.0), 0.0);
    }

    @Test
    void nonJunkFoodGetsNegativePenaltyAdjustment() {
        assertEquals(-1.0, FoodGimmickPolicy.inversionSaturationAdjustment(false, 2.0, 1.0), 0.0);
    }

    @Test
    void nonFiniteOrNegativeBonusPenaltyTreatedAsZero() {
        assertEquals(0.0, FoodGimmickPolicy.inversionSaturationAdjustment(true, Double.NaN, 1.0), 0.0);
        assertEquals(0.0, FoodGimmickPolicy.inversionSaturationAdjustment(true, -5.0, 1.0), 0.0);
        assertEquals(0.0, FoodGimmickPolicy.inversionSaturationAdjustment(false, 2.0, Double.NEGATIVE_INFINITY), 0.0);
        assertEquals(0.0, FoodGimmickPolicy.inversionSaturationAdjustment(false, 2.0, -3.0), 0.0);
    }
}
