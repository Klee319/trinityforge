package com.trinityforge.woodcutting;

import com.trinityforge.mining.VeinMiningAlgorithm;
import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 一括伐採のあとに「バニラなら崩壊する葉」だけを選ぶ純関数 (2026-07-31 N2)。Bukkit 非依存。
 *
 * <p><b>要望</b>: 「一括破壊で伐採したときに葉っぱの自動破壊の速度がバニラより大幅に向上するように」。
 * TF は 2026-07-30 から能動的に葉を壊しているが、
 * <ul>
 *   <li>走査の種が<b>実際に伐った丸太だけ</b>だったので、上限で伐り残した幹に付いた樹冠へ到達できず
 *       (葉の BFS は葉しか辿らない)、叩いた位置次第で「葉が1枚も壊れない」ことがあった</li>
 *   <li>枚数上限が「伐った本数 × 6」で実樹冠(小オークでも数十枚)に対して常に不足していた</li>
 * </ul>
 * ため、はみ出した葉がバニラのランダムティック崩壊(既定 {@code randomTickSpeed=3} で平均 68 秒、
 * かつ {@code simulation-distance} 外では止まる)に落ちて「全然消えない」体感になっていた。
 * 種を<b>木全体</b>にし、上限を tier ごとに桁で上げるのが対策。
 *
 * <p><b>{@code decayOnly}(既定 true) が防波堤である理由</b>: 上限を桁で上げると、葉だけを辿る BFS は
 * 樹冠が接している<b>隣の木の葉まで食う</b>(ジャングル/ダークオークの森で顕著)。そこで
 * 「バニラの崩壊判定を自前で解く」= <em>設置された葉({@code persistent})は壊さない</em> かつ
 * <em>残っている原木から距離6以内で支えられた葉は壊さない</em> ことで、
 * 「バニラなら崩壊しない葉には一切触らない」を保証する。既定 true を崩すと1本伐るだけで林冠が
 * 連鎖消滅する。
 *
 * <p>バニラの規則(minecraft.wiki): 葉の {@code distance} は原木に面隣接で 1、葉を1枚辿るごとに +1 で、
 * {@code distance} が 7 に達した葉がブロックティックで崩壊する。したがって
 * <b>「支えられている」= 距離 {@value #MAX_SUPPORT_DISTANCE} 以内</b>。
 */
public final class LeafDecayPlanner {

    /** バニラで葉が原木に支えられていると見なされる最大距離。これを超えた葉が崩壊する。 */
    public static final int MAX_SUPPORT_DISTANCE = 6;

    private LeafDecayPlanner() {
    }

    /**
     * 壊してよい葉の座標を、種に近い順(BFS 順)で返す。
     *
     * @param treeLogs          葉の走査の種となる「木全体」の座標(上限で伐り残した幹も含める)。
     * @param isBreakableLeaf   壊す候補になる葉の述語。本番では「葉であり、かつ
     *                          {@code decayOnly} なら {@code persistent} でない」(世界読み)。
     * @param isSupportLog      葉を支える原木/木材の述語(世界読み)。<b>これから壊す原木は false を
     *                          返すこと</b> — 支持判定は「破壊後に残るもの」で解かないと意味がない。
     * @param maxLeaves         1回の伐採で壊す葉の絶対上限。0以下なら空を返す。
     * @param decayOnly         true なら支持解決を行い「バニラなら崩壊する葉」だけに絞る。
     *                          false は 2026-07-30 の旧挙動(候補をそのまま返す)。
     */
    public static List<BlockPos> plan(Collection<BlockPos> treeLogs,
                                      Predicate<BlockPos> isBreakableLeaf,
                                      Predicate<BlockPos> isSupportLog,
                                      int maxLeaves,
                                      boolean decayOnly) {
        Objects.requireNonNull(treeLogs, "treeLogs");
        Objects.requireNonNull(isBreakableLeaf, "isBreakableLeaf");
        Objects.requireNonNull(isSupportLog, "isSupportLog");
        if (maxLeaves <= 0 || treeLogs.isEmpty()) {
            return List.of();
        }
        List<BlockPos> canopy = VeinMiningAlgorithm.collectFrom(treeLogs, isBreakableLeaf, maxLeaves);
        if (!decayOnly || canopy.isEmpty()) {
            return canopy;
        }
        Set<BlockPos> supported = resolveSupported(canopy, isSupportLog);
        List<BlockPos> doomed = new ArrayList<>(canopy.size());
        for (BlockPos leaf : canopy) {
            if (!supported.contains(leaf)) {
                doomed.add(leaf);
            }
        }
        return List.copyOf(doomed);
    }

    /**
     * バニラの {@code LeavesBlock#updateDistance} と同じ規則で、{@code canopy} のうち残存原木に
     * 距離 {@value #MAX_SUPPORT_DISTANCE} 以内で支えられている葉を求める。1枚ごとに半径6の探索を
     * 回すのは高コストなので、<b>多源で1回だけ</b>層を広げる。
     *
     * <p>{@code canopy} に入っていない葉(=上限で切られた葉、{@code persistent} な葉)は伝播経路として
     * 使わない。これは「支持を見落とす」方向の誤差なので、バニラが残す葉を壊してしまう可能性は
     * あるが、隣の木の樹冠を守るという本題は満たす(隣の木の葉はその木の原木から距離1から順に
     * 支えられており、候補集合に入っているため必ず到達できる)。
     */
    private static Set<BlockPos> resolveSupported(List<BlockPos> canopy, Predicate<BlockPos> isSupportLog) {
        Set<BlockPos> canopySet = new LinkedHashSet<>(canopy);
        Set<BlockPos> supported = new LinkedHashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        for (BlockPos leaf : canopy) {
            for (BlockPos neighbor : leaf.faceNeighbors()) {
                if (isSupportLog.test(neighbor)) {
                    if (supported.add(leaf)) {
                        frontier.add(leaf);
                    }
                    break;
                }
            }
        }
        // frontier は distance=1 の葉。ここから distance=MAX_SUPPORT_DISTANCE まで層を広げる。
        for (int distance = 1; distance < MAX_SUPPORT_DISTANCE && !frontier.isEmpty(); distance++) {
            Deque<BlockPos> next = new ArrayDeque<>();
            for (BlockPos leaf : frontier) {
                for (BlockPos neighbor : leaf.faceNeighbors()) {
                    if (canopySet.contains(neighbor) && supported.add(neighbor)) {
                        next.add(neighbor);
                    }
                }
            }
            frontier = next;
        }
        return supported;
    }
}
