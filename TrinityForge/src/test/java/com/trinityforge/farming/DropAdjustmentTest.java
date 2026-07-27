package com.trinityforge.farming;

import com.trinityforge.farming.DropAdjustment.DropStack;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

/** {@link DropAdjustment}: seed-subtraction for auto-replant, without mutating the input list. */
class DropAdjustmentTest {

    @Test
    void subtractsOneFromMatchingSeedStack() {
        List<DropStack> drops = List.of(
                new DropStack(Material.WHEAT, 1),
                new DropStack(Material.WHEAT_SEEDS, 3));

        List<DropStack> result = DropAdjustment.subtractOne(drops, Material.WHEAT_SEEDS);

        assertEquals(2, result.size());
        assertIterableEquals(List.of(
                new DropStack(Material.WHEAT, 1),
                new DropStack(Material.WHEAT_SEEDS, 2)), result);
    }

    @Test
    void removesStackEntirelyWhenItReachesZero() {
        List<DropStack> drops = List.of(
                new DropStack(Material.CARROT, 1));

        List<DropStack> result = DropAdjustment.subtractOne(drops, Material.CARROT);

        assertEquals(List.of(), result);
    }

    @Test
    void onlySubtractsFromTheFirstMatchingStack() {
        List<DropStack> drops = List.of(
                new DropStack(Material.NETHER_WART, 2),
                new DropStack(Material.NETHER_WART, 5));

        List<DropStack> result = DropAdjustment.subtractOne(drops, Material.NETHER_WART);

        assertIterableEquals(List.of(
                new DropStack(Material.NETHER_WART, 1),
                new DropStack(Material.NETHER_WART, 5)), result);
    }

    @Test
    void noMatchingSeedStackLeavesDropsUnchanged() {
        List<DropStack> drops = List.of(new DropStack(Material.WHEAT, 1));

        List<DropStack> result = DropAdjustment.subtractOne(drops, Material.WHEAT_SEEDS);

        assertIterableEquals(drops, result);
    }

    @Test
    void nullSeedMaterialLeavesDropsUnchanged() {
        List<DropStack> drops = List.of(new DropStack(Material.WHEAT, 1));

        List<DropStack> result = DropAdjustment.subtractOne(drops, null);

        assertIterableEquals(drops, result);
    }

    @Test
    void inputListIsNeverMutated() {
        List<DropStack> drops = List.of(new DropStack(Material.CARROT, 2));

        DropAdjustment.subtractOne(drops, Material.CARROT);

        // `drops` is List.of(...) (immutable) so any accidental in-place mutation would have thrown;
        // reaching here with the original value intact confirms no mutation occurred.
        assertEquals(2, drops.get(0).amount());
    }
}
