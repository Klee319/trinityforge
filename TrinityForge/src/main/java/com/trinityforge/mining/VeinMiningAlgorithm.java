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

    /**
     * 面隣接6方向 + <b>「1段上がりながら水平に1マスずれる」8方向</b>(2026-08-18 W-98)。
     *
     * <p><b>なぜ幹だけ別の近傍が要るのか</b> — バニラのアカシア/ジャングルの幹は
     * {@code BendingTrunkPlacer} が置く。これは1ループで「水平へ1マス動く → その位置に原木を置く →
     * 上へ1マス動く」を行うので、<b>曲がり目の連続する原木どうしは {@code (±1, +1, 0)} だけずれた
     * 斜め隣接になり、面では繋がっていない</b>。面隣接6方向の BFS はそこで必ず打ち切られるため、
     * 一括伐採がアカシアの曲がった先を伐り残していた(実サーバ報告
     * 「アカシアなどのくねくねしてる原木も一括破壊出来るようにしてほしい」)。
     *
     * <p><b>26近傍にはしない。</b> 水平だけの斜め({@code dy == 0} で x/z が同時にずれる)を許すと、
     * 隣り合って生えた別の木の幹へ同じ高さで飛び移れてしまう。曲がり幹が必要としているのは
     * 「上下に1段ずれる斜め」だけなので、そこだけ足す。角(3軸同時)も同じ理由で入れない。
     *
     * <p>鉱石の一括採掘({@link #collect} の既定)は<b>面隣接のままにする</b> — 角で触れただけの
     * 別鉱脈を巻き込まない、というクラス冒頭の設計判断は変えない。
     */
    private static final int[][] BENT_TRUNK_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1},
            {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
            {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1},
    };

    private VeinMiningAlgorithm() {
    }

    /** Integer block coordinate, Bukkit-free (mirrors what a {@code Block}'s x/y/z would give). */
    public record BlockPos(int x, int y, int z) {

        public BlockPos {
        }

        public List<BlockPos> faceNeighbors() {
            return offsetBy(FACE_OFFSETS);
        }

        /**
         * 面隣接6方向 + 上下に1段ずれる斜め8方向。曲がり幹(アカシア/ジャングル)の走査用。
         * 根拠は {@link #BENT_TRUNK_OFFSETS} の javadoc。
         */
        public List<BlockPos> bentTrunkNeighbors() {
            return offsetBy(BENT_TRUNK_OFFSETS);
        }

        private List<BlockPos> offsetBy(int[][] offsets) {
            List<BlockPos> neighbors = new ArrayList<>(offsets.length);
            for (int[] offset : offsets) {
                neighbors.add(new BlockPos(x + offset[0], y + offset[1], z + offset[2]));
            }
            return neighbors;
        }
    }

    /** 近傍の取り方。{@link BlockPos#faceNeighbors} / {@link BlockPos#bentTrunkNeighbors} を渡す。 */
    @FunctionalInterface
    public interface Adjacency {
        List<BlockPos> neighborsOf(BlockPos pos);
    }

    /** 面隣接6方向(鉱脈の既定)。 */
    public static final Adjacency FACE = BlockPos::faceNeighbors;

    /** 面隣接 + 上下1段の斜め(曲がり幹用)。 */
    public static final Adjacency BENT_TRUNK = BlockPos::bentTrunkNeighbors;

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
        return collect(start, isTarget, maxExtra, FACE);
    }

    /**
     * {@link #collect(BlockPos, Predicate, int)} の近傍を差し替えられる版(2026-08-18 W-98)。
     * 一括伐採は {@link #BENT_TRUNK} を渡す — 曲がり幹は面では繋がっていない。
     */
    public static List<BlockPos> collect(BlockPos start, Predicate<BlockPos> isTarget, int maxExtra,
                                         Adjacency adjacency) {
        Objects.requireNonNull(start, "start");
        return collectFrom(List.of(start), isTarget, maxExtra, adjacency);
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
        return collectFrom(sources, isTarget, maxExtra, FACE);
    }

    /**
     * {@link #collectFrom(java.util.Collection, Predicate, int)} の近傍を差し替えられる版
     * (2026-08-18 W-98)。
     */
    public static List<BlockPos> collectFrom(java.util.Collection<BlockPos> sources,
                                             Predicate<BlockPos> isTarget, int maxExtra,
                                             Adjacency adjacency) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(isTarget, "isTarget");
        Objects.requireNonNull(adjacency, "adjacency");
        List<BlockPos> result = new ArrayList<>();
        if (maxExtra <= 0 || sources.isEmpty()) {
            return result;
        }

        // LinkedHashSet keeps the traversal (and therefore the result) order a pure function of the
        // source order, so the same tree always yields the same set regardless of hash iteration order.
        Set<BlockPos> visited = new LinkedHashSet<>(sources);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos source : sources) {
            for (BlockPos neighbor : adjacency.neighborsOf(source)) {
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
            for (BlockPos neighbor : adjacency.neighborsOf(pos)) {
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return result;
    }
}
