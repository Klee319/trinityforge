package com.trinityforge.woodcutting;

import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LeafDecayPlanner} (2026-07-31 N2 実サーバ要望「一括破壊で伐採したときに葉っぱの自動破壊の速度が
 * バニラより大幅に向上するように」): 種が木全体であること、{@code decayOnly} が「バニラなら崩壊しない葉」
 * — 設置された葉と残存原木に支えられた葉 — に触らないことを固定する。
 */
class LeafDecayPlannerTest {

    private static Predicate<BlockPos> memberOf(Set<BlockPos> positions) {
        return positions::contains;
    }

    private static final Predicate<BlockPos> NO_SUPPORT = pos -> false;

    @Test
    void everyCanopyLeafIsDoomedWhenTheWholeTrunkIsGone() {
        // 幹が全部消えた木: 樹冠を支えるものが無いのでバニラなら全部崩壊する。
        Set<BlockPos> leaves = new HashSet<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                leaves.add(new BlockPos(x, 5, z));
            }
        }
        List<BlockPos> trunk = List.of(new BlockPos(0, 4, 0));

        List<BlockPos> doomed = LeafDecayPlanner.plan(trunk, memberOf(leaves), NO_SUPPORT, 512, true).doomed();

        assertEquals(leaves.size(), doomed.size(), "樹冠25枚すべてが崩壊対象");
        assertEquals(leaves, Set.copyOf(doomed));
    }

    @Test
    void leavesOfANeighbouringTreeWithItsOwnTrunkAreNotTouched() {
        // 樹冠が癒着した2本の木。x<=0 側の幹だけ伐り、x=3 に隣の木の幹が残っている状況。
        Set<BlockPos> leaves = new HashSet<>();
        for (int x = 0; x <= 3; x++) {
            leaves.add(new BlockPos(x, 5, 0));
        }
        BlockPos neighbourTrunk = new BlockPos(3, 4, 0);
        List<BlockPos> felledTrunk = List.of(new BlockPos(0, 4, 0));

        List<BlockPos> doomed = LeafDecayPlanner.plan(felledTrunk, memberOf(leaves),
                memberOf(Set.of(neighbourTrunk)), 512, true).doomed();

        assertFalse(doomed.contains(new BlockPos(3, 5, 0)),
                "隣の木の幹の真上の葉(距離1)は残す");
        assertTrue(doomed.isEmpty(),
                "距離6以内なので癒着部分は丸ごと残す(林冠の連鎖消滅を防ぐ防波堤)");
    }

    @Test
    void supportDistanceBoundaryMatchesVanillaSixVersusSeven() {
        // 残存原木から水平に伸びた葉の帯。距離6の葉は残り、距離7の葉は崩壊する。
        Set<BlockPos> leaves = new HashSet<>();
        for (int x = 1; x <= 7; x++) {
            leaves.add(new BlockPos(x, 5, 0));
        }
        BlockPos supportLog = new BlockPos(0, 5, 0);
        // 種は「伐った幹」。葉の帯の一番遠い側から辿れるように x=8 を種に置く。
        List<BlockPos> felledTrunk = List.of(new BlockPos(8, 5, 0));

        List<BlockPos> doomed = LeafDecayPlanner.plan(felledTrunk, memberOf(leaves),
                memberOf(Set.of(supportLog)), 512, true).doomed();

        assertEquals(List.of(new BlockPos(7, 5, 0)), doomed,
                "距離7(=原木から葉7枚目)だけが崩壊し、距離6までは支えられて残ること");
    }

    @Test
    void persistentLeavesAreExcludedByTheCandidatePredicate() {
        // 本番の候補述語は「葉であり、かつ decayOnly なら persistent でない」。
        // ここでは装飾の葉壁(persistent)を候補から外した状態を再現する。
        Set<BlockPos> naturalLeaves = Set.of(new BlockPos(0, 5, 0), new BlockPos(1, 5, 0));
        Set<BlockPos> persistentLeaves = Set.of(new BlockPos(2, 5, 0));
        List<BlockPos> trunk = List.of(new BlockPos(0, 4, 0));

        List<BlockPos> doomed = LeafDecayPlanner.plan(trunk,
                pos -> naturalLeaves.contains(pos) && !persistentLeaves.contains(pos),
                NO_SUPPORT, 512, true).doomed();

        assertEquals(naturalLeaves, Set.copyOf(doomed));
        assertFalse(doomed.contains(new BlockPos(2, 5, 0)), "設置された葉は壊さない");
    }

    @Test
    void maxLeavesCapsTheResult() {
        Set<BlockPos> leaves = new HashSet<>();
        for (int x = 1; x <= 50; x++) {
            leaves.add(new BlockPos(x, 5, 0));
        }
        List<BlockPos> trunk = List.of(new BlockPos(0, 5, 0));

        assertEquals(10, LeafDecayPlanner.plan(trunk, memberOf(leaves), NO_SUPPORT, 10, true).size());
        assertTrue(LeafDecayPlanner.plan(trunk, memberOf(leaves), NO_SUPPORT, 0, true).isEmpty());
        assertTrue(LeafDecayPlanner.plan(trunk, memberOf(leaves), NO_SUPPORT, -3, true).isEmpty());
        assertFalse(LeafDecayPlanner.plan(trunk, memberOf(leaves), NO_SUPPORT, 10, true).budgetExhausted(),
                "1本の種から辿る密な葉列は予算に当たらない");
    }

    @Test
    void decayOnlyFalseKeepsTheLegacyBehaviourOfBreakingEverythingReachable() {
        Set<BlockPos> leaves = Set.of(new BlockPos(1, 5, 0), new BlockPos(2, 5, 0));
        BlockPos supportLog = new BlockPos(3, 5, 0);
        List<BlockPos> trunk = List.of(new BlockPos(0, 5, 0));

        List<BlockPos> doomed = LeafDecayPlanner.plan(trunk, memberOf(leaves),
                memberOf(Set.of(supportLog)), 512, false).doomed();

        assertEquals(leaves, Set.copyOf(doomed),
                "decayOnly=false は 2026-07-30 の旧挙動(支持を見ずに全部壊す)");
    }

    @Test
    void seedingFromTheUnfelledUpperTrunkReachesTheCanopyTheFelledLogsCannot() {
        // 上限で伐り残した幹(y=8..11)にだけ樹冠が付いている木。伐った丸太(y=0..7)だけを種にすると
        // 葉の BFS は葉しか辿らないので樹冠へ到達できない = 旧実装で「葉が1枚も壊れない」だった形。
        Set<BlockPos> leaves = Set.of(new BlockPos(1, 12, 0), new BlockPos(0, 12, 0));
        List<BlockPos> felledOnly = List.of(
                new BlockPos(0, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, 2, 0), new BlockPos(0, 3, 0),
                new BlockPos(0, 4, 0), new BlockPos(0, 5, 0), new BlockPos(0, 6, 0), new BlockPos(0, 7, 0));
        List<BlockPos> wholeTree = new java.util.ArrayList<>(felledOnly);
        for (int y = 8; y <= 11; y++) {
            wholeTree.add(new BlockPos(0, y, 0));
        }

        assertTrue(LeafDecayPlanner.plan(felledOnly, memberOf(leaves), NO_SUPPORT, 512, false).isEmpty(),
                "伐った丸太だけを種にすると樹冠へ到達できない(旧実装の欠陥)");
        assertEquals(leaves, Set.copyOf(
                        LeafDecayPlanner.plan(wholeTree, memberOf(leaves), NO_SUPPORT, 512, false).doomed()),
                "木全体を種にすれば伐り残した幹の樹冠も候補に入ること");
    }

    // --- 2026-07-31 G1 レビュー指摘6a: 候補収集の明示的な予算 ---

    @Test
    void probeBudgetStopsCandidateCollectionWhenMostProbedPositionsAreRejected() {
        // 高い幹(種100本) + 小さい leaves-max。collectFrom は「受理した数」でしか止まらないので、
        // 種の面隣接リング(6 x 100 = 600件)を全部読むまで走る余地があった。予算(8 x maxLeaves)で
        // 打ち切ることを固定する。
        List<BlockPos> trunk = new java.util.ArrayList<>();
        for (int y = 0; y < 100; y++) {
            trunk.add(new BlockPos(0, y, 0));
        }
        Set<BlockPos> unreachableLeaves = Set.of(new BlockPos(5, 50, 0));

        LeafDecayPlanner.Plan plan =
                LeafDecayPlanner.plan(trunk, memberOf(unreachableLeaves), NO_SUPPORT, 5, true);

        assertTrue(plan.budgetExhausted(), "予算に当たったことを呼び出し側へ知らせること(WARNINGの根拠)");
        assertEquals(5 * LeafDecayPlanner.PROBE_BUDGET_FACTOR, plan.probes(),
                "予算ぴったりで世界読みを止めること");
        assertTrue(plan.doomed().isEmpty(), "届かない葉は計画に入らない");
    }

    @Test
    void aDenseCanopyNeverHitsTheProbeBudget() {
        // 実樹冠(葉が密)では受理率が高いので予算に当たらないこと = 正常系を殺していないことの確認。
        Set<BlockPos> leaves = new HashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 5; y <= 7; y++) {
                    leaves.add(new BlockPos(x, y, z));
                }
            }
        }
        List<BlockPos> trunk = List.of(new BlockPos(0, 4, 0));

        LeafDecayPlanner.Plan plan = LeafDecayPlanner.plan(trunk, memberOf(leaves), NO_SUPPORT, 512, true);

        assertFalse(plan.budgetExhausted(), "密な樹冠は予算に当たらないこと");
        assertEquals(leaves.size(), plan.size(), "7x7x3=147枚すべてが崩壊対象");
    }
}
