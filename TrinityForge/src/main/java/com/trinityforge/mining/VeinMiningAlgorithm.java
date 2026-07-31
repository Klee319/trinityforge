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
        return collectFrom(List.of(start), isTarget, maxExtra);
    }

    /**
     * Multi-source variant of {@link #collect}: the search starts from the face-neighbors of
     * <em>every</em> position in {@code sources}, and none of the sources themselves can appear in the
     * result. Used by 一括伐採の葉の巻き込み (the felled trunk is many blocks, and the canopy touches it
     * at many different points — a single-source search from the originally broken log would stop as
     * soon as the trunk was already gone).
     *
     * <p><b>2026-07-31 N1:</b> the seed ring is marked visited <em>on enqueue</em>, exactly like every
     * later ring. The previous shape enqueued every source's six face-neighbors unconditionally and only
     * then bulk-marked them, so two sources that share a face-neighbor (any 2×2 trunk — dark oak/jungle —
     * or any two diagonally adjacent logs) enqueued the same position twice and it could be
     * <em>returned twice</em>, silently consuming the caller's budget with duplicates. Single-source
     * {@link #collect} is unaffected (its six neighbors are always distinct).
     */
    public static List<BlockPos> collectFrom(java.util.Collection<BlockPos> sources,
                                             Predicate<BlockPos> isTarget, int maxExtra) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(isTarget, "isTarget");
        List<BlockPos> result = new ArrayList<>();
        if (maxExtra <= 0 || sources.isEmpty()) {
            return result;
        }

        // LinkedHashSet keeps the traversal (and therefore the result) order a pure function of the
        // source order, so the same tree always yields the same set regardless of hash iteration order.
        Set<BlockPos> visited = new LinkedHashSet<>(sources);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos source : sources) {
            for (BlockPos neighbor : source.faceNeighbors()) {
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

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
