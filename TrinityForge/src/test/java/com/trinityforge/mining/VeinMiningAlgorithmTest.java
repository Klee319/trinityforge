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

    // --- 2026-07-31 N1: collectFrom(多源)の多重 enqueue ---

    @Test
    void multiSourceCollectNeverReturnsTheSamePositionTwice() {
        // 斜めに隣接する2つのソースは面隣接を共有する((1,0,0) と (0,1,0) が両方から1手)。
        // 旧実装は初期リングを「全ソース分 enqueue してから visited へ一括投入」していたため、
        // 共有された位置が2回キューに入り result に重複して現れ、呼び出し側の予算を無言で溶かしていた。
        List<BlockPos> sources = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 1, 0));
        Set<BlockPos> targets = Set.of(new BlockPos(1, 0, 0), new BlockPos(0, 1, 0));

        List<BlockPos> result = VeinMiningAlgorithm.collectFrom(sources, targetSet(targets), 32);

        assertEquals(2, result.size(), "重複を含まないこと(旧実装は4件返していた)");
        assertEquals(targets, Set.copyOf(result));
        assertEquals(result.size(), Set.copyOf(result).size(), "result に重複が無いこと");
    }

    @Test
    void multiSourceCollectExcludesEverySourceFromTheResult() {
        List<BlockPos> sources = List.of(new BlockPos(0, 0, 0), new BlockPos(2, 0, 0));
        Set<BlockPos> targets = Set.of(
                new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 0, 0));

        List<BlockPos> result = VeinMiningAlgorithm.collectFrom(sources, targetSet(targets), 32);

        assertEquals(Set.of(new BlockPos(1, 0, 0), new BlockPos(3, 0, 0)), Set.copyOf(result));
    }

    @Test
    void multiSourceCollectIsDeterministicForTheSameSourceOrder() {
        List<BlockPos> sources = List.of(new BlockPos(0, 0, 0), new BlockPos(0, 3, 0));
        java.util.HashSet<BlockPos> targets = new java.util.HashSet<>();
        for (int y = -3; y <= 6; y++) {
            targets.add(new BlockPos(0, y, 0));
        }

        List<BlockPos> first = VeinMiningAlgorithm.collectFrom(sources, targetSet(targets), 5);
        List<BlockPos> second = VeinMiningAlgorithm.collectFrom(sources, targetSet(targets), 5);

        assertEquals(first, second, "同じ入力なら順序まで同一(打ち切り位置が安定すること)");
        assertEquals(5, first.size());
    }
}
