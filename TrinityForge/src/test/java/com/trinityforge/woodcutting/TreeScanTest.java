package com.trinityforge.woodcutting;

import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TreeScan} (2026-07-31 N1 実サーバ報告「伐採で底面と側面から壊したときと真上から壊したときとで
 * 壊れる範囲が違う」): 走査の錨が「叩いたブロック」ではなく木そのものであること = 同じ木のどの高さから
 * 呼んでも同じ集合が返ることを固定する。
 */
class TreeScanTest {

    private static Predicate<BlockPos> trunkOf(Set<BlockPos> logs) {
        return logs::contains;
    }

    /** 高さ {@code height} の垂直な幹柱を (0, 0, 0) から生成する。 */
    private static Set<BlockPos> trunkColumn(int height) {
        Set<BlockPos> logs = new HashSet<>();
        for (int y = 0; y < height; y++) {
            logs.add(new BlockPos(0, y, 0));
        }
        return logs;
    }

    @Test
    void trunkBaseDescendsToTheLowestLogFromAnyHeight() {
        Set<BlockPos> logs = trunkColumn(12);
        BlockPos expected = new BlockPos(0, 0, 0);

        for (int y = 0; y < 12; y++) {
            assertEquals(expected, TreeScan.trunkBase(new BlockPos(0, y, 0), trunkOf(logs)),
                    "y=" + y + " から呼んでも幹の最下段が返ること");
        }
    }

    @Test
    void trunkBaseStopsAtTheDescentGuard() {
        // 述語が常に true(=無限に幹が続く)でもガードで止まること。
        BlockPos base = TreeScan.trunkBase(new BlockPos(0, 0, 0), pos -> true);

        assertEquals(-TreeScan.TRUNK_BASE_MAX_DESCENT, base.y(),
                "暴走防止ガードの段数だけ降りて止まること");
    }

    @Test
    void wholeTreeIsSortedBottomUpAndIncludesTheBase() {
        Set<BlockPos> logs = trunkColumn(5);

        List<BlockPos> tree = TreeScan.wholeTree(new BlockPos(0, 0, 0), trunkOf(logs), TreeScan.TREE_SCAN_LIMIT);

        assertEquals(List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(0, 1, 0),
                new BlockPos(0, 2, 0),
                new BlockPos(0, 3, 0),
                new BlockPos(0, 4, 0)), tree);
    }

    @Test
    void wholeTreeIsCappedByTheScanLimitIndependentlyOfTheFellingCap() {
        Set<BlockPos> logs = trunkColumn(40);

        List<BlockPos> tree = TreeScan.wholeTree(new BlockPos(0, 0, 0), trunkOf(logs), 10);

        assertEquals(10, tree.size(), "走査上限は base を含めた本数");
    }

    /** {@code origin}(イベント本体が壊す1本)と連鎖対象を合わせた「実際に消えるブロック」の集合。 */
    private static Set<BlockPos> removedBy(Set<BlockPos> logs, int struckY, int maxExtra) {
        BlockPos origin = new BlockPos(0, struckY, 0);
        BlockPos base = TreeScan.trunkBase(origin, trunkOf(logs));
        List<BlockPos> tree = TreeScan.wholeTree(base, trunkOf(logs), TreeScan.TREE_SCAN_LIMIT);
        Set<BlockPos> removed = new HashSet<>(TreeScan.selectFelled(tree, origin, maxExtra));
        removed.add(origin);
        return removed;
    }

    @Test
    void treeUnderTheCapIsFullyFelledFromAnyStruckBlock() {
        // 上限に収まる木(7段 + 上限8本): 最下段/中段/最上段のどこを叩いても消えるブロック集合が完全一致。
        Set<BlockPos> logs = trunkColumn(7);

        Set<BlockPos> fromBottom = removedBy(logs, 0, 8);
        Set<BlockPos> fromMiddle = removedBy(logs, 3, 8);
        Set<BlockPos> fromTop = removedBy(logs, 6, 8);

        assertEquals(logs, fromBottom, "木全体が倒れること");
        assertEquals(fromBottom, fromMiddle);
        assertEquals(fromBottom, fromTop);
    }

    @Test
    void cappedTreeRemovesTheSameBlocksFromAnyStruckBlockInsideTheFelledWindow() {
        // 12段 + 上限8本 = 伐採される窓は根元から9段(連鎖8本 + 叩いた1本)。
        // その窓の中のどこを叩いても、消えるブロック集合が完全一致すること(回帰ガード本体)。
        Set<BlockPos> logs = trunkColumn(12);
        Set<BlockPos> expected = new HashSet<>();
        for (int y = 0; y <= 8; y++) {
            expected.add(new BlockPos(0, y, 0));
        }

        for (int struckY = 0; struckY <= 8; struckY++) {
            assertEquals(expected, removedBy(logs, struckY, 8),
                    "y=" + struckY + " を叩いても根元から9段が消えること");
        }
    }

    @Test
    void cappedTreeStruckAboveTheWindowStillFellsFromTheRootUpwards() {
        // 窓より上(y=9..11)を叩いた場合だけは、叩いた1本がイベント本体に壊されるぶん消える集合が
        // 1ブロック増える — これは避けられない(TF はイベント対象の破壊を止められない)。
        // 重要なのは「連鎖対象は常に根元から」で、旧実装のように起点相対に等方展開して
        // 根元が伐り残る(切り株が浮く)ことが無いこと。
        Set<BlockPos> logs = trunkColumn(12);

        for (int struckY = 9; struckY <= 11; struckY++) {
            BlockPos origin = new BlockPos(0, struckY, 0);
            BlockPos base = TreeScan.trunkBase(origin, trunkOf(logs));
            List<BlockPos> tree = TreeScan.wholeTree(base, trunkOf(logs), TreeScan.TREE_SCAN_LIMIT);

            List<BlockPos> selected = TreeScan.selectFelled(tree, origin, 8);

            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), selected.stream().map(BlockPos::y).toList(),
                    "y=" + struckY + " を叩いても連鎖対象は根元から8本であること");
        }
    }

    @Test
    void cappedFellingAlwaysLeavesTheCrownNotTheStump() {
        Set<BlockPos> logs = trunkColumn(12);
        BlockPos origin = new BlockPos(0, 11, 0); // 真上から叩いた(旧実装では根元が伐り残っていた)
        BlockPos base = TreeScan.trunkBase(origin, trunkOf(logs));
        List<BlockPos> tree = TreeScan.wholeTree(base, trunkOf(logs), TreeScan.TREE_SCAN_LIMIT);

        List<BlockPos> selected = TreeScan.selectFelled(tree, origin, 8);

        assertEquals(8, selected.size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), selected.stream().map(BlockPos::y).toList(),
                "根元から上へ8本。残るのは樹冠側(y=8..10)であること");
        assertTrue(selected.stream().noneMatch(pos -> pos.equals(origin)),
                "叩いた1本はイベント本体が壊すので連鎖対象に含めない");
    }

    @Test
    void selectFelledIsDeterministicForABranchingTreeRegardlessOfInputOrder() {
        // 2×2 幹(ダークオーク型)。HashSet の反復順に結果が依存しないことを、入力順を変えて確認する。
        Set<BlockPos> logs = new HashSet<>();
        for (int y = 0; y < 4; y++) {
            logs.add(new BlockPos(0, y, 0));
            logs.add(new BlockPos(1, y, 0));
            logs.add(new BlockPos(0, y, 1));
            logs.add(new BlockPos(1, y, 1));
        }
        BlockPos base = new BlockPos(0, 0, 0);
        List<BlockPos> tree = TreeScan.wholeTree(base, trunkOf(logs), TreeScan.TREE_SCAN_LIMIT);
        List<BlockPos> shuffled = new java.util.ArrayList<>(tree);
        java.util.Collections.reverse(shuffled);

        assertEquals(TreeScan.selectFelled(tree, base, 6), TreeScan.selectFelled(shuffled, base, 6),
                "入力の並びを変えても同じ順序・同じ集合が返ること");
        assertEquals(List.of(0, 0, 0, 1, 1, 1), TreeScan.selectFelled(tree, base, 6).stream()
                .map(BlockPos::y).toList(), "y 昇順で下から採ること");
    }
}
