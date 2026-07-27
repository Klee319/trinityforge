package com.trinityforge.mining;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure connected-component search over an integer 3D grid (deliberately Bukkit-free so it is
 * unit-testable without a running server/World). Backs the {@code vein-mining} dedicated-effect
 * (the {@code dedicated-effects:} field on each node in {@code skilltree/*.yml}): breaking one ore block chain-breaks every same-type ore
 * block directly reachable from it.
 *
 * <p><strong>6-directional (face) adjacency, not 26 (face+edge+corner):</strong> "vein" ore
 * generation in vanilla connects blocks that share a face, not merely a corner/edge, so 6-directional
 * search matches player intuition of "the same vein" and avoids chain-breaking two visually separate
 * veins that happen to touch only diagonally.
 */
public final class VeinMiningAlgorithm {

    private static final int[][] FACE_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1},
    };

    private VeinMiningAlgorithm() {
    }

    /** Integer block coordinate, Bukkit-free (mirrors what a {@code Block}'s x/y/z would give). */
    public record BlockPos(int x, int y, int z) {

        public BlockPos {
        }

        public List<BlockPos> faceNeighbors() {
            List<BlockPos> neighbors = new ArrayList<>(FACE_OFFSETS.length);
            for (int[] offset : FACE_OFFSETS) {
                neighbors.add(new BlockPos(x + offset[0], y + offset[1], z + offset[2]));
            }
            return neighbors;
        }
    }

    /**
     * Breadth-first search from {@code start}'s face-neighbors outward, following only positions for
     * which {@code isTarget} is {@code true} (i.e. same ore type as the originally-broken block).
     * {@code start} itself is never included in the result (the caller's own break event already
     * handles it) but IS marked visited so the search never walks back through it.
     *
     * @param start     the originally-broken block's position (excluded from the result)
     * @param isTarget  predicate selecting which positions count as "part of the vein" (a live world
     *                  read in production; a fixed in-memory grid in tests)
     * @param maxExtra  hard cap on the number of additional blocks returned — bounds both the
     *                  traversal and the result size so a huge/degenerate vein can never cause
     *                  unbounded work or an unbounded number of extra block breaks in one event
     * @return up to {@code maxExtra} connected same-type positions, excluding {@code start}
     */
    public static List<BlockPos> collect(BlockPos start, Predicate<BlockPos> isTarget, int maxExtra) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(isTarget, "isTarget");
        List<BlockPos> result = new ArrayList<>();
        if (maxExtra <= 0) {
            return result;
        }

        Set<BlockPos> visited = new LinkedHashSet<>();
        visited.add(start);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(start.faceNeighbors());
        // Seed neighbors are enqueued once; mark them visited only when dequeued/accepted below so a
        // position reachable via two different paths is not enqueued twice from this first ring... but
        // to keep the guard simple and correct we mark on enqueue instead (see loop).
        visited.addAll(queue);

        while (!queue.isEmpty() && result.size() < maxExtra) {
            BlockPos pos = queue.poll();
            if (!isTarget.test(pos)) {
                continue;
            }
            result.add(pos);
            if (result.size() >= maxExtra) {
                break;
            }
            for (BlockPos neighbor : pos.faceNeighbors()) {
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return result;
    }
}
