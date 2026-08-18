package com.trinityforge.woodcutting;

import com.trinityforge.mining.VeinMiningAlgorithm;
import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 一括伐採の走査範囲を「叩いたブロック」ではなく<b>木そのもの</b>に錨づけるための純関数
 * (2026-07-31 N1)。Bukkit 非依存なので {@link VeinMiningAlgorithm} と同じくサーバ無しで単体テストできる。
 *
 * <p><b>直した不具合</b>: 以前は {@code BlockBreakEvent} の対象ブロックをそのまま BFS の起点にしていた。
 * BFS は起点相対に等方展開するので、幹の本数が上限を超える木では<b>「どこを叩いたか」で伐れる範囲が
 * 変わっていた</b>(実サーバ報告「底面と側面から壊したときと真上から壊したときとで壊れる範囲が違う」)。
 * 上面を叩けば起点は幹の最上段になり上限に達した時点で根元が伐り残って切り株が浮き、側面/底面を叩けば
 * 根元から上へ伐れる、という具合に結果が起点依存だった。
 *
 * <p><b>直し方</b>: {@link #trunkBase} で幹の最下段まで降りてから走査を始め、伐る本数は
 * {@link #BOTTOM_UP}({@code y→x→z} の全順序)で決定的に選ぶ。これで {@code (木, 上限)} の純関数になり、
 * 叩いた面・叩いた高さに関係なく同じ集合が伐れる。上限に当たったときに残るのは常に<b>樹冠側</b>
 * (=y が大きい方)。
 *
 * <p><b>残余(2026-07-31 G1 レビュー指摘7/8 — 原理的に直せない/直さないもの)</b>
 * <ul>
 *   <li><b>伐採窓(根元から {@code maxExtra + 1} 段)より上を叩くと、消えるブロックの集合が1つ増える。</b>
 *       {@link #selectFelled} が返す<em>連鎖対象</em>は常に「根元から {@code maxExtra} 本」で起点に
 *       依存しないが、叩いた1本は {@code BlockBreakEvent} 本体が壊すので TF には止められない。
 *       結果 y=11 を叩くと y8..y10 が浮いたまま y11 だけ抜けた見た目になる。したがって
 *       <b>{@code extra} は {@code (木, 上限)} の純関数だが、「実際に消える集合」は起点が窓の内か外かで
 *       1ブロック変わる</b>(実装者の以前の説明はここが厳密でなかった)。</li>
 *   <li><b>連結原木が {@code scanLimit} を超える塊では面依存が残る。</b> {@link #trunkBase} は
 *       「同一列を真下へ」なので 2×2 幹や壁では叩いた列ごとに異なる base を返し、{@link #wholeTree} は
 *       base 起点の BFS を {@code scanLimit} 本で打ち切る。連結成分が上限を超えると収集される部分集合が
 *       base 依存になり {@link #selectFelled} の結果も叩いた列に依存する。バニラ樹木(最大でも百数十本)
 *       では到達しないが、丸太建築や {@link #TRUNK_BASE_MAX_DESCENT} 段を超える巨大構造では復活する。
 *       丸太建築側は {@code PlacedBlockTracker} で走査を止める(呼び出し側の
 *       {@code TreeFellingListener} 参照)ことで実害を潰してある。</li>
 * </ul>
 *
 * <p><b>2026-07-31 G1 round2 レビュー指摘2: 記録に乗らない丸太建築への第二の歯止め。</b>
 * {@code PlacedBlockTracker} による除外は「{@code BlockPlaceEvent} を通って置かれた丸太」しか覆えない
 * (WorldEdit / schematic / {@code /setblock} / ピストンで動いた丸太 / チャンク上限 FIFO で追い出された
 * マーク は全部「自然木」として走査される)。そこで {@link #withinDistance} を走査の述語に AND して
 * <b>叩いた位置から一定距離を超える丸太を最初から見ない</b>ようにし、記録に乗らない建築でも
 * 「視界外のブロックが消える」最悪ケースを消す。既定値は実在するバニラ樹木の寸法より大きいので
 * 自然樹には影響しない(値の根拠は {@code stats/woodcutting-gimmick.yml} のコメント)。
 */
public final class TreeScan {

    /**
     * {@link #trunkBase} が真下へ降りる最大段数。幹が異常に長い(あるいは述語が常に true を返す)
     * ケースでメインスレッドが無限に降り続けないための暴走防止ガード。バニラの最長樹木でも
     * 30 段程度なので実用上当たらない。
     */
    public static final int TRUNK_BASE_MAX_DESCENT = 64;

    /**
     * 「木全体」を把握するときの走査上限(本数)の<b>既定値</b>。<b>伐る本数の上限
     * ({@code max-extra-logs})とは別枠</b>で持つ — 上限で伐り残した幹も葉の走査の種に含める必要が
     * あるため(N2)。メインスレッドで最大この回数のブロック読みが走るので、大きくしすぎないこと。
     *
     * <p>2026-07-31 G1 レビュー指摘6b で {@code tree-fell.scan-limit} として yml へ出したので、
     * 本番の値は {@code WoodcuttingGimmickConfig#treeFellScanLimit()} が持つ。ここはその既定値と
     * 「config を読めない純関数テスト」用の定数。<b>片方だけ変えるとドリフトする。</b>
     */
    public static final int TREE_SCAN_LIMIT = 512;

    /**
     * 決定的な全順序: {@code y} 昇順 → {@code x} 昇順 → {@code z} 昇順。「根元から上へ伐る」ための順で、
     * かつ {@code HashSet} の反復順に結果が依存しないようにするためのタイブレークでもある。
     */
    public static final Comparator<BlockPos> BOTTOM_UP = Comparator
            .comparingInt(BlockPos::y)
            .thenComparingInt(BlockPos::x)
            .thenComparingInt(BlockPos::z);

    private TreeScan() {
    }

    /**
     * 「叩いた位置 {@code origin} から一定距離の内側か」を判定する述語(2026-07-31 G1 round2 指摘2)。
     * 走査の述語に AND して使う — 距離判定はワールドを読まないので<b>材質判定より前に置くこと</b>
     * (範囲外の位置で {@code getBlockAt} も PDC 走査も走らせないため)。
     *
     * <p>水平は {@code x}/{@code z} 各軸のチェビシェフ距離、垂直は {@code y} の差で見る(球ではなく
     * 直方体) — 木は縦に長く横に短いので、軸ごとに別の上限を持てる形が要件に合う。
     *
     * @param maxHorizontal {@code |dx|} と {@code |dz|} の上限。<b>0以下は「水平方向は無制限」</b>。
     * @param maxVertical   {@code |dy|} の上限。<b>0以下は「垂直方向は無制限」</b>。
     * @return 範囲内なら true を返す述語(両方が0以下なら常に true = 2026-07-31 以前の挙動)
     */
    public static Predicate<BlockPos> withinDistance(BlockPos origin, int maxHorizontal, int maxVertical) {
        Objects.requireNonNull(origin, "origin");
        if (maxHorizontal <= 0 && maxVertical <= 0) {
            return pos -> true;
        }
        return pos -> {
            if (maxVertical > 0 && Math.abs(pos.y() - origin.y()) > maxVertical) {
                return false;
            }
            return maxHorizontal <= 0
                    || (Math.abs(pos.x() - origin.x()) <= maxHorizontal
                        && Math.abs(pos.z() - origin.z()) <= maxHorizontal);
        };
    }

    /**
     * {@code origin} と同じ幹柱の最下段を返す。{@code y-1} が {@code isTrunk} を満たす限り真下へ降りる
     * ので、同じ幹柱のどのブロックから呼んでも同じ座標が返る(=面依存が消える一点)。
     *
     * @param isTrunk 幹として扱う位置の述語(本番では「破壊されたブロックと同じ Material か」)
     */
    public static BlockPos trunkBase(BlockPos origin, Predicate<BlockPos> isTrunk) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(isTrunk, "isTrunk");
        BlockPos base = origin;
        for (int descended = 0; descended < TRUNK_BASE_MAX_DESCENT; descended++) {
            BlockPos below = new BlockPos(base.x(), base.y() - 1, base.z());
            if (!isTrunk.test(below)) {
                return base;
            }
            base = below;
        }
        return base;
    }

    /**
     * {@code base} から面隣接で繋がる幹を最大 {@code scanLimit} 本まで集め、{@link #BOTTOM_UP} 順に
     * 並べて返す。{@code base} 自身を含む — <b>ただし {@code base} も {@code isTrunk} で検査する</b>。
     *
     * <p><b>2026-07-31 G1 round2 指摘10</b>: 以前は {@code base} を無条件に結果へ入れていた。
     * 述語が「自然木の丸太か」を意味する本番では、<em>設置された丸太を叩くとその1本が木として残り</em>
     * 葉の BFS の種になっていた(呼び出し側は木全体を種にするため)。周囲の自然原木が距離6以内で
     * 支えるので実害は「森の中の丸太1本を壊すと近傍の自然葉が数枚壊れて CT を取る」程度だったが、
     * 「設置丸太は木ではない」という本クラスの主張とは食い違っていた。空を返せば呼び出し側の
     * {@code planLeaves} も {@code treeLogs.isEmpty()} で即座に降りる。
     *
     * <p><b>2026-08-18 W-98: 走査は {@link VeinMiningAlgorithm#BENT_TRUNK}(面隣接 + 上下1段の斜め)で
     * 行う。</b> バニラのアカシア/ジャングルの幹は {@code BendingTrunkPlacer} が
     * 「水平へ1マス → 置く → 上へ1マス」で曲げるため、<b>曲がり目の連続する原木が斜め隣接になり
     * 面では繋がっていない</b>。面隣接だけで BFS するとそこで必ず打ち切られ、曲がった先が丸ごと
     * 伐り残っていた(実サーバ報告「アカシアなどのくねくねしてる原木も一括破壊出来るようにしてほしい」)。
     * 水平だけの斜めと角は入れていない — 隣り合って生えた別の木へ飛び移らせないため。
     *
     * @param scanLimit {@code base} を含めた本数の上限。0以下なら空を返す。
     */
    public static List<BlockPos> wholeTree(BlockPos base, Predicate<BlockPos> isTrunk, int scanLimit) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(isTrunk, "isTrunk");
        if (scanLimit <= 0 || !isTrunk.test(base)) {
            return List.of();
        }
        List<BlockPos> tree = new ArrayList<>();
        tree.add(base);
        tree.addAll(VeinMiningAlgorithm.collect(
                base, isTrunk, scanLimit - 1, VeinMiningAlgorithm.BENT_TRUNK));
        tree.sort(BOTTOM_UP);
        return List.copyOf(tree);
    }

    /**
     * 実際に連鎖伐採する幹を選ぶ。{@code tree} から {@code origin}(=イベント本体が壊す1本)を除き、
     * {@link #BOTTOM_UP} 順に先頭から {@code maxExtra} 本を採る。
     *
     * <p>これが「常に根元から上へ伐る／残るのは樹冠側」の実装。{@code tree} の並びに依存しないよう
     * ここでも並べ直す(呼び出し側が {@link #wholeTree} 以外を渡してもよいようにするため)。
     */
    public static List<BlockPos> selectFelled(List<BlockPos> tree, BlockPos origin, int maxExtra) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(origin, "origin");
        if (maxExtra <= 0 || tree.isEmpty()) {
            return List.of();
        }
        return tree.stream()
                .filter(pos -> !pos.equals(origin))
                .sorted(BOTTOM_UP)
                .limit(maxExtra)
                .toList();
    }
}
