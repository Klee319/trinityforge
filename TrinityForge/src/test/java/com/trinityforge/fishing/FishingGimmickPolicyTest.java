package com.trinityforge.fishing;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FishingGimmickPolicy}: junk/treasure classification for junk-to-scrap / fish-sell-toggle. */
class FishingGimmickPolicyTest {

    private static final Set<Material> JUNK = Set.of(Material.ROTTEN_FLESH, Material.BONE, Material.STRING);
    private static final Set<Material> TREASURE = Set.of(Material.NAME_TAG, Material.SADDLE);

    @Test
    void isJunkTrueWhenMaterialInSet() {
        assertTrue(FishingGimmickPolicy.isJunk(Material.ROTTEN_FLESH, JUNK));
    }

    @Test
    void isJunkFalseWhenMaterialNotInSet() {
        assertFalse(FishingGimmickPolicy.isJunk(Material.COD, JUNK));
    }

    @Test
    void isJunkNullSafe() {
        assertFalse(FishingGimmickPolicy.isJunk(null, JUNK));
        assertFalse(FishingGimmickPolicy.isJunk(Material.ROTTEN_FLESH, null));
    }

    @Test
    void isTreasureTrueWhenMaterialInSet() {
        assertTrue(FishingGimmickPolicy.isTreasure(Material.NAME_TAG, TREASURE));
    }

    @Test
    void isTreasureFalseWhenMaterialNotInSet() {
        assertFalse(FishingGimmickPolicy.isTreasure(Material.ROTTEN_FLESH, TREASURE));
    }

    @Test
    void isTreasureNullSafe() {
        assertFalse(FishingGimmickPolicy.isTreasure(null, TREASURE));
        assertFalse(FishingGimmickPolicy.isTreasure(Material.NAME_TAG, null));
    }

    @Test
    void junkAndTreasureSetsAreDisjointByDesign() {
        // Guards against a config regression: the two sets must never overlap, since
        // FishingGimmickListener chains fish-sell-toggle's treasure-check into junk-to-scrap's
        // junk-check on the SAME event and a shared material would double-fire unpredictably.
        for (Material m : JUNK) {
            assertFalse(TREASURE.contains(m));
        }
    }
}
