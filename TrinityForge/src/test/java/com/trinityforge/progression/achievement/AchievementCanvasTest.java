package com.trinityforge.progression.achievement;

import com.trinityforge.config.domains.AchievementsConfig.Achievement;
import com.trinityforge.config.domains.AchievementsConfig.Rewards;
import com.trinityforge.config.domains.AchievementsConfig.Trigger;
import com.trinityforge.config.domains.AchievementsConfig.TriggerType;
import com.trinityforge.skilltree.generator.Coord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /achievement} GUI の格子投影 (2026-07-29)。Bukkit を使わないヘッドレス検証。
 *
 * <p>ここで守りたいのは「設定を書き間違えてもGUIが落ちない」こと: 存在しない前提ID、循環、
 * 座標の重複があっても、全ノードがどこかに1つずつ置かれて描ける状態になること。
 */
class AchievementCanvasTest {

    private static final Rewards NO_REWARDS =
            new Rewards(List.of(), List.of(), List.of(), 0, List.of(), Map.of());
    private static final Trigger TRIGGER =
            new Trigger(TriggerType.ADVANCEMENT, null, 0, "minecraft:story/mine_diamond", null, List.of(), false);

    private static Achievement achievement(String id, String coords, String parent, List<String> parentsAny) {
        return new Achievement(id, id, TRIGGER, false, NO_REWARDS, "", List.of(), coords, parent, parentsAny);
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> AchievementCanvas.project(List.of()));
    }

    @Test
    void placesEveryNodeExactlyOnce() {
        List<Achievement> achievements = List.of(
                achievement("root", "", null, List.of()),
                achievement("a", "", "root", List.of()),
                achievement("b", "", "root", List.of()),
                achievement("c", "", "a", List.of()));

        AchievementCanvas canvas = AchievementCanvas.project(achievements);

        assertEquals(4, canvas.nodes().size());
        long distinctPoints = canvas.nodes().values().stream()
                .map(AchievementCanvas.NodeCell::point).distinct().count();
        assertEquals(4, distinctPoints, "ノードが同じセルに重ならないこと");
    }

    @Test
    void childrenSitBelowTheirParent() {
        List<Achievement> achievements = List.of(
                achievement("root", "", null, List.of()),
                achievement("child", "", "root", List.of()));

        AchievementCanvas canvas = AchievementCanvas.project(achievements);

        int rootY = canvas.nodes().get("root").point().y();
        int childY = canvas.nodes().get("child").point().y();
        assertTrue(childY > rootY, "y は下方向に増えるので、子は親より下 (root=" + rootY + ", child=" + childY + ")");
    }

    @Test
    void explicitCoordsWin() {
        List<Achievement> achievements = List.of(
                achievement("root", "10,4", null, List.of()),
                achievement("child", "", "root", List.of()));

        AchievementCanvas canvas = AchievementCanvas.project(achievements);

        // 正規化で全体が平行移動するので絶対値ではなく相対関係で見る。
        // root は coords:10,4 で固定、child は自動配置(深さ1 → y=2)なので差は 4-2=2。
        AchievementCanvas.Point root = canvas.nodes().get("root").point();
        AchievementCanvas.Point child = canvas.nodes().get("child").point();
        assertEquals(2, root.y() - child.y(), "明示 coords が自動配置に上書きされないこと");
        assertEquals(10 - 0, root.x() - child.x(), "x も明示値がそのまま効くこと");
    }

    @Test
    void drawsConnectorsBetweenParentAndChild() {
        List<Achievement> achievements = List.of(
                achievement("root", "0,0", null, List.of()),
                achievement("child", "0,2", "root", List.of()));

        AchievementCanvas canvas = AchievementCanvas.project(achievements);

        AchievementCanvas.Point center = canvas.nodes().get("root").point();
        boolean hasConnector = canvas.viewport(center).values().stream()
                .anyMatch(cell -> cell instanceof AchievementCanvas.ConnectorCell);
        assertTrue(hasConnector, "親子の間に線が引かれること");
    }

    @Test
    void connectorOwnerIsTheChildSoColourFollowsTheTarget() {
        List<Achievement> achievements = List.of(
                achievement("root", "0,0", null, List.of()),
                achievement("child", "0,2", "root", List.of()));

        AchievementCanvas canvas = AchievementCanvas.project(achievements);

        AchievementCanvas.ConnectorCell connector = canvas.viewport(canvas.start()).values().stream()
                .filter(AchievementCanvas.ConnectorCell.class::isInstance)
                .map(AchievementCanvas.ConnectorCell.class::cast)
                .findFirst().orElse(null);
        assertNotNull(connector);
        assertEquals(java.util.Set.of("child"), connector.ownerIds());
    }

    @Test
    void unknownPrerequisiteIsSkippedWithoutBreakingTheCanvas() {
        List<Achievement> achievements = List.of(
                achievement("orphan", "", "does-not-exist", List.of()),
                achievement("other", "", null, List.of()));

        AchievementCanvas canvas = AchievementCanvas.project(achievements);

        assertEquals(2, canvas.nodes().size(), "不明な前提を持つノードも描画対象から消えないこと");
    }

    @Test
    void cyclicPrerequisitesStillProjectEveryNode() {
        List<Achievement> achievements = List.of(
                achievement("a", "", "b", List.of()),
                achievement("b", "", "a", List.of()));

        AchievementCanvas canvas = AchievementCanvas.project(achievements);

        assertEquals(2, canvas.nodes().size(), "循環していても無限ループせず全ノードを置くこと");
    }

    @Test
    void parentsAnyIsDrawnAsALineToo() {
        List<Achievement> achievements = List.of(
                achievement("a", "0,0", null, List.of()),
                achievement("b", "4,0", null, List.of()),
                achievement("either", "2,2", null, List.of("a", "b")));

        AchievementCanvas canvas = AchievementCanvas.project(achievements);

        long owned = canvas.viewport(canvas.focusOn("either")).values().stream()
                .filter(AchievementCanvas.ConnectorCell.class::isInstance)
                .map(AchievementCanvas.ConnectorCell.class::cast)
                .filter(cell -> cell.ownerIds().contains("either"))
                .count();
        assertTrue(owned >= 2, "parents-any の2本ぶんの線が出ること (実際=" + owned + ")");
    }

    @Test
    void prerequisiteIdsFoldsParentAndParentsAnyWithoutDuplicates() {
        Achievement achievement = achievement("x", "", "a", List.of("a", "b"));
        assertEquals(List.of("a", "b"), AchievementCanvas.prerequisiteIds(achievement));
    }

    @Test
    void viewportIsNineByFiveAndClampsAtTheEdges() {
        AchievementCanvas canvas = AchievementCanvas.project(List.of(
                achievement("only", "", null, List.of())));

        // 極端に外へ振っても中心はクランプされ、ビューポートは常に45スロット内に収まる。
        AchievementCanvas.Point far = canvas.move(canvas.start(), 999, 999);
        assertTrue(canvas.viewport(far).keySet().stream().allMatch(slot -> slot >= 0 && slot < 45));
        assertEquals(canvas.clamp(far), far, "move の結果は既にクランプ済みであること");
    }

    @Test
    void focusOnUnknownIdFallsBackToStart() {
        AchievementCanvas canvas = AchievementCanvas.project(List.of(
                achievement("only", "", null, List.of())));
        assertEquals(canvas.start(), canvas.focusOn("nope"));
    }

    @Test
    void parseCoordsAcceptsSpacesAndRejectsGarbage() {
        assertEquals(new Coord(3, 4), AchievementCanvas.AchievementLayout.parseCoords(" 3 , 4 "));
        assertNull(AchievementCanvas.AchievementLayout.parseCoords(""));
        assertNull(AchievementCanvas.AchievementLayout.parseCoords("3"));
        assertNull(AchievementCanvas.AchievementLayout.parseCoords("x,y"));
    }

    @Test
    void duplicateExplicitCoordsDoNotSwallowANode() {
        // 同じ座標を2件に書いてしまった場合、後勝ちで消えるのではなく自動配置に回ること。
        List<Achievement> achievements = List.of(
                achievement("a", "0,0", null, List.of()),
                achievement("b", "0,0", null, List.of()));

        AchievementCanvas canvas = AchievementCanvas.project(achievements);

        assertEquals(2, canvas.nodes().size());
        assertTrue(!canvas.nodes().get("a").point().equals(canvas.nodes().get("b").point()));
    }

    @Test
    void assignRespectsExplicitCoordsForTheParentChain() {
        Map<String, Coord> coords = AchievementCanvas.AchievementLayout.assign(List.of(
                achievement("root", "6,0", null, List.of()),
                achievement("child", "", "root", List.of())));

        assertEquals(new Coord(6, 0), coords.get("root"));
        assertEquals(2, coords.get("child").y(), "子は深さ1 (STEP=2) の行に来ること");
    }
}
