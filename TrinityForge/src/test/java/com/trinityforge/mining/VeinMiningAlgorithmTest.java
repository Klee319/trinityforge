package com.trinityforge.mining;

import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VeinMiningAlgorithm} pure flood-fill: connectivity, cap enforcement, and diagonal-only
 * (non-face) blocks being excluded by the 6-directional adjacency choice.
 */
class VeinMiningAlgorithmTest {

    private static Predicate<BlockPos> targetSet(Set<BlockPos> targets) {
        return targets::contains;
    }

    @Test
    void collectsAllConnectedTargetsExcludingStart() {
        BlockPos start = new BlockPos(0, 0, 0);
        Set<BlockPos> ore = Set.of(
                start,
                new BlockPos(1, 0, 0),
                new BlockPos(2, 0, 0),
                new BlockPos(2, 1, 0));

        List<BlockPos> extra = VeinMiningAlgorithm.collect(start, targetSet(ore), 32);

        assertEquals(3, extra.size());
        assertTrue(extra.contains(new BlockPos(1, 0, 0)));
        assertTrue(extra.contains(new BlockPos(2, 0, 0)));
        assertTrue(extra.contains(new BlockPos(2, 1, 0)));
        assertTrue(extra.stream().noneMatch(pos -> pos.equals(start)));
    }

    @Test
    void stopsAtDisconnectedOreDoesNotJumpAcrossAGap() {
        BlockPos start = new BlockPos(0, 0, 0);
        // (5,0,0) is same-type ore but not reachable through the target set (gap at x=1..4).
        Set<BlockPos> ore = Set.of(start, new BlockPos(5, 0, 0));

        List<BlockPos> extra = VeinMiningAlgorithm.collect(start, targetSet(ore), 32);

        assertTrue(extra.isEmpty());
    }

    @Test
    void diagonalOnlyNeighborIsNotCollected() {
        // 6-directional (face) adjacency deliberately excludes a purely diagonal neighbor.
        BlockPos start = new BlockPos(0, 0, 0);
        Set<BlockPos> ore = Set.of(start, new BlockPos(1, 1, 0));

        List<BlockPos> extra = VeinMiningAlgorithm.collect(start, targetSet(ore), 32);

        assertTrue(extra.isEmpty());
    }

    @Test
    void respectsMaxExtraCapOnALargeVein() {
        BlockPos start = new BlockPos(0, 0, 0);
        // A long straight line of 100 connected ore blocks along +x.
        java.util.HashSet<BlockPos> line = new java.util.HashSet<>();
        for (int x = 0; x <= 100; x++) {
            line.add(new BlockPos(x, 0, 0));
        }

        List<BlockPos> extra = VeinMiningAlgorithm.collect(start, targetSet(line), 10);

        assertEquals(10, extra.size());
    }

    @Test
    void zeroOrNegativeCapReturnsEmpty() {
        BlockPos start = new BlockPos(0, 0, 0);
        Set<BlockPos> ore = Set.of(start, new BlockPos(1, 0, 0));

        assertTrue(VeinMiningAlgorithm.collect(start, targetSet(ore), 0).isEmpty());
        assertTrue(VeinMiningAlgorithm.collect(start, targetSet(ore), -5).isEmpty());
    }
}
