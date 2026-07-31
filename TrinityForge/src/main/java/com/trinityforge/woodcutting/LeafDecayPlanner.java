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
 *
 * <p><b>残余(2026-07-31 G1 レビュー指摘6/9 — 直さないと決めたもの)</b>
 * <ul>
 *   <li><b>幹を伐り切れない木では葉が1枚も壊れない。</b> {@code decayOnly} は定義上「バニラなら崩壊する葉」
 *       だけを壊すので、伐採上限({@code max-extra-logs})を超える高さの木では残存した幹が樹冠を距離6以内で
 *       支え続け、計画が空になる。これはバグではなく仕様(バニラも崩壊させない)。対策は
 *       {@code decayOnly} を緩めることではなく<b>幹を伐り切れるところまで {@code max-extra-logs} を
 *       上げること</b>で、出荷 tier を 8/16/32/64 → 16/32/64/128 に上げたのがそれ
 *       ({@code stats/woodcutting-gimmick.yml})。それでも上限を超える巨木(トウヒの2×2 巨木など)では
 *       依然として葉が残る。</li>
 *   <li><b>候補集合の外を伝播経路に使わない</b>ので、装飾の葉壁({@code persistent})を経由して支えられている
 *       自然葉を「支持なし」と誤判定して壊すことがある({@link #resolveSupported} 参照)。バニラより
 *       <em>多く</em>壊す方向の誤差。</li>
 * </ul>
 */
public final class LeafDecayPlanner {

    /** バニラで葉が原木に支えられていると見なされる最大距離。これを超えた葉が崩壊する。 */
    public static final int MAX_SUPPORT_DISTANCE = 6;

    /**
     * 候補収集で {@code isBreakableLeaf} を呼んでよい回数の予算係数(2026-07-31 G1 レビュー指摘6a)。
     * 予算 = {@code maxLeaves × この値}。
     *
     * <p><b>なぜ必要か</b>: {@link VeinMiningAlgorithm#collectFrom} は「受理した数」が上限に達したら
     * 止まるが、<em>棄却</em>した位置の数は上限に掛からない。受理1件ごとに面隣接6件が enqueue され、
     * その大半は空気/原木で棄却される(=世界読みだけして捨てる)ので、実際の述語呼び出し回数は
     * 受理数の数倍になる。tier4(1024枚)では約6千〜1万回の {@code getBlockAt} が1tickに乗る。
     * ここで明示的に天井を作り、超えたら打ち切って呼び出し側が WARNING を出せるようにする。
     *
     * <p>係数8は「面隣接6 + 種のリング分」を丸めた値。出荷値(tier1=128 〜 tier4=1024)では
     * <b>実在する樹木では当たらない</b> — 種のリングは {@code visited} に種を先に入れてあるので
     * 重複が潰れ、ジャングルの2×2 巨木(幹120本前後)でも露出面は200〜300件程度に収まり、
     * tier1 の予算1024に対して十分な余裕がある。
     *
     * <p><b>当たったときの挙動(honest degradation)</b>: 例外は投げず、<em>そこまでに集めた候補だけ</em>で
     * 計画する。つまり葉の掃除が「途中まで」になる(0枚になるのではない)。呼び出し側が WARNING を
     * 出すので、当たったら {@code leaves-max} を上げるか {@code scan-limit} を下げるのが正しい対処。
     * 当たり得るのは「幹の露出面が {@code leaves-max × 8} を超える異常に大きな連結原木」か
     * 「{@code leaves-max} を極端に小さくした設定」のときだけ。
     */
    public static final int PROBE_BUDGET_FACTOR = 8;

    private LeafDecayPlanner() {
    }

    /**
     * 計画の結果。
     *
     * @param doomed          壊してよい葉の座標(種に近い順)。
     * @param probes          {@code isBreakableLeaf} を実際に呼んだ回数(=世界読みの実測値)。
     * @param budgetExhausted 予算({@link #PROBE_BUDGET_FACTOR} × {@code maxLeaves})に当たって
     *                        候補収集を打ち切ったか。true なら樹冠の一部が計画に入っていない。
     */
    public record Plan(List<BlockPos> doomed, int probes, boolean budgetExhausted) {

        public Plan {
            doomed = List.copyOf(doomed);
        }

        public static Plan empty() {
            return new Plan(List.of(), 0, false);
        }

        public boolean isEmpty() {
            return doomed.isEmpty();
        }

        public int size() {
            return doomed.size();
        }
    }

    /**
     * 壊してよい葉の座標を、種に近い順(BFS 順)で返す。
     *
     * <p><b>tick 分散はここでは行わない(2026-07-31 G1 レビュー指摘6 の「やらない判断」)</b>:
     * 計画は<em>破壊前の世界</em>を1枚のスナップショットとして解く純関数である。tick を跨いで
     * 分割すると、その間に別のプレイヤー/別の伐採/バニラのランダムティック崩壊が世界を変えるので、
     * 前半と後半で「支えている原木」の集合が違う計画が混ざり、
     * <ul>
     *   <li>支持解決(多源1回の層展開)を分割できない — 距離6の層は候補集合が全部揃ってからでないと
     *       解けず、途中の集合で解くと「まだ収集していない葉から支えられている」を見落として
     *       過剰破壊する</li>
     *   <li>CT の消費判定({@code TreeFellingListener}: 仕事が0なら消費しない)が次tickまで確定しない</li>
     * </ul>
     * という二重の破綻になる。代わりに<b>予算({@link #PROBE_BUDGET_FACTOR})で総量を抑え、
     * 実際の破壊だけを {@code leaves-per-tick} で分散する</b>。実測が本当に足りなくなったら、
     * 分散ではなく {@code leaves-max} / {@code scan-limit} を下げるのが正しい対処。
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
    public static Plan plan(Collection<BlockPos> treeLogs,
                            Predicate<BlockPos> isBreakableLeaf,
                            Predicate<BlockPos> isSupportLog,
                            int maxLeaves,
                            boolean decayOnly) {
        Objects.requireNonNull(treeLogs, "treeLogs");
        Objects.requireNonNull(isBreakableLeaf, "isBreakableLeaf");
        Objects.requireNonNull(isSupportLog, "isSupportLog");
        if (maxLeaves <= 0 || treeLogs.isEmpty()) {
            return Plan.empty();
        }
        // 予算を超えたら「候補ではない」を返して BFS を枯らす(例外は投げない — ブロック破壊
        // ハンドラから投げてはいけないし、途中まででも掃除できた方が体感が良い)。
        int probeBudget = maxLeaves > Integer.MAX_VALUE / PROBE_BUDGET_FACTOR
                ? Integer.MAX_VALUE
                : maxLeaves * PROBE_BUDGET_FACTOR;
        int[] probes = {0};
        Predicate<BlockPos> budgeted = pos -> {
            if (probes[0] >= probeBudget) {
                return false;
            }
            probes[0]++;
            return isBreakableLeaf.test(pos);
        };
        List<BlockPos> canopy = VeinMiningAlgorithm.collectFrom(treeLogs, budgeted, maxLeaves);
        boolean exhausted = probes[0] >= probeBudget;
        if (!decayOnly || canopy.isEmpty()) {
            return new Plan(canopy, probes[0], exhausted);
        }
        Set<BlockPos> supported = resolveSupported(canopy, isSupportLog);
        List<BlockPos> doomed = new ArrayList<>(canopy.size());
        for (BlockPos leaf : canopy) {
            if (!supported.contains(leaf)) {
                doomed.add(leaf);
            }
        }
        return new Plan(doomed, probes[0], exhausted);
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
