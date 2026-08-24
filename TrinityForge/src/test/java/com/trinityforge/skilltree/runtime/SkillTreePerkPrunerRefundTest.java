package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ツリーから消えたノードの SP が実際に戻ってくることを、本物のリポジトリで通す
 * (2026-08-24 / W-213 「スキルツリーを編集するとリセットされるが SP が消失する」)。
 *
 * <p>ここで固定するのは判定ではなく<b>決済</b>: perk 行が消えること、available が増えて
 * spent が同じだけ減ること(= {@code available + spent} が保存され、
 * {@code ProgressionCurveReconciler} の {@code available = earned - spent} と食い違わないこと)、
 * そして<b>掃除の前は振り直しでも救済されなかった</b>ことの証拠。
 */
class SkillTreePerkPrunerRefundTest {

    private static final String SKILL = SkillId.WOODCUTTING;
    private static final String PERK_A = "woodcutting_perk_a";
    private static final String PERK_A2 = "woodcutting_perk_a_2";

    private SqliteProgressionRepository repository;
    private NativeProgressionService progression;
    private NativePerkService perks;
    private SkillTreePerkPruner pruner;
    /** 「configを編集した」を再現するための差し替え可能なツリー供給元。 */
    private final List<SkillTree> trees = new ArrayList<>();
    private UUID playerId;

    @BeforeEach
    void setUp() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        repository = new SqliteProgressionRepository("jdbc:sqlite::memory:");
        progression = new NativeProgressionService(repository, catalog);
        Collection<SkillTree> supplier = trees;
        perks = new NativePerkService(progression, () -> supplier);
        pruner = new SkillTreePerkPruner(progression, () -> supplier, () -> true);
        playerId = UUID.randomUUID();

        trees.add(tree("A", "A-2"));
        // その スキルの行を作る(解放判定はレベルを読むので行が無いと進めない)。
        progression.grantExp(playerId, SKILL, 1.0);
        repository.savePointBalance(playerId, 5L, 0L);

        assertEquals(NativePerkService.UnlockResult.UNLOCKED, perks.unlock(playerId, SKILL, "A"));
        assertEquals(NativePerkService.UnlockResult.UNLOCKED, perks.unlock(playerId, SKILL, "A-2"));
        assertEquals(3L, balance()[0], "1点ずつ2ノード解放したので残 3");
        assertEquals(2L, balance()[1]);
    }

    @AfterEach
    void tearDown() throws Exception {
        repository.close();
    }

    @Test
    void deletedNodeGivesTheSkillPointBack() {
        // config編集で A-2 を削除した。
        trees.clear();
        trees.add(tree("A"));

        SkillTreePerkPruner.PruneResult result = pruner.pruneAll();

        assertEquals(1, result.players());
        assertEquals(1, result.perks());
        assertEquals(1L, result.refundedPoints());
        assertEquals(Set.of(PERK_A), owned(), "消えたノードのperkだけが剥がれる");
        assertEquals(4L, balance()[0], "支払った1点が available へ戻る");
        assertEquals(1L, balance()[1], "spent からも同じ1点が引かれる");
        // available + spent が保存されていないと、次の reload で
        // ProgressionCurveReconciler が available を上書きして返却が消える。
        assertEquals(5L, balance()[0] + balance()[1]);
    }

    @Test
    void resetTreeAloneNeverRecoveredIt() {
        // 掃除を入れる前の状態の証拠: 振り直しは「今あるノード」しか返却しないので、
        // 消えたノードの支払いは spent に残り続ける(これが SP 消失の実体)。
        trees.clear();
        trees.add(tree("A"));

        assertEquals(NativePerkService.ResetResult.RESET, perks.resetTree(playerId, SKILL));
        assertEquals(Set.of(PERK_A2), owned(), "A-2 のperk行だけが取り残される");
        assertEquals(4L, balance()[0]);
        assertEquals(1L, balance()[1], "A-2 の1点は振り直しでは戻らない");

        // 掃除を通すとここで初めて回収される。
        assertEquals(1, pruner.pruneAll().perks());
        assertTrue(owned().isEmpty());
        assertEquals(5L, balance()[0]);
        assertEquals(0L, balance()[1]);
    }

    @Test
    void abrokenTreeLoadSuspendsTheSweepInsteadOfStrippingEverything() {
        // 安全弁: 直前の読み込みに問題があった回は何もしない。ymlが一時的に壊れただけで
        // 全員の解放を剥がすほうが、SPが数点戻らないより桁違いに悪い。
        trees.clear();
        trees.add(tree("A"));
        SkillTreePerkPruner suspended =
                new SkillTreePerkPruner(progression, () -> trees, () -> false);

        assertTrue(suspended.pruneAll().isEmpty());
        assertEquals(Set.of(PERK_A, PERK_A2), owned(), "掃除を見送った回はperkに触らない");
        assertEquals(3L, balance()[0]);
        assertEquals(2L, balance()[1]);
    }

    @Test
    void aTreeThatFailedToLoadAtAllIsNotTreatedAsDeletedNodes() {
        // ツリーが1本も読めていない状態(=そのスキルが丸ごと消えたように見える)でも剥がさない。
        trees.clear();

        assertTrue(pruner.pruneAll().isEmpty());
        assertEquals(Set.of(PERK_A, PERK_A2), owned());
        assertFalse(owned().isEmpty());
    }

    /** {@code [available, spent]}。 */
    private long[] balance() {
        var snapshot = progression.snapshot(playerId);
        return new long[] {snapshot.availablePoints(), snapshot.spentPoints()};
    }

    private Set<String> owned() {
        return repository.loadPerkIds(playerId).orElseThrow();
    }

    private static SkillTree tree(String... nodeIds) {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        String previous = null;
        for (String id : nodeIds) {
            nodes.put(id, new SkillNode(id, id, 0, SkillRole.MAIN, previous, null, "STONE", 1,
                    "", Map.of(), Map.of(), List.of(), List.of(), List.of()));
            previous = id;
        }
        return new SkillTree(SKILL, "伐採", "IRON_AXE", "2,10", null, nodes);
    }
}
