package com.trinityforge.skilltree.runtime;

import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeSkillTreeCanvasTest {

    @Test
    void projectsNodesAndConnectionsIntoNineByFiveViewport() {
        NativeSkillTreeCanvas canvas = NativeSkillTreeCanvas.project(tree());
        Map<Integer, NativeSkillTreeCanvas.Cell> viewport = canvas.viewport(canvas.start());

        assertTrue(viewport.values().stream().anyMatch(NativeSkillTreeCanvas.NodeCell.class::isInstance));
        assertTrue(viewport.values().stream().anyMatch(NativeSkillTreeCanvas.ConnectorCell.class::isInstance));
        assertTrue(viewport.keySet().stream().allMatch(slot -> slot >= 0 && slot < 45));
    }

    @Test
    void nodeCellsTakePriorityOverCrossingConnectors() {
        NativeSkillTreeCanvas canvas = NativeSkillTreeCanvas.project(tree());

        for (NativeSkillTreeCanvas.NodeCell node : canvas.nodes().values()) {
            assertInstanceOf(NativeSkillTreeCanvas.NodeCell.class, canvas.cells().get(node.point()));
        }
    }

    @Test
    void diagonalMovementClampsEachAxisIndependently() {
        NativeSkillTreeCanvas canvas = NativeSkillTreeCanvas.project(tree());
        var northWestEdge = new NativeSkillTreeCanvas.Point(canvas.minCenterX(), canvas.minCenterY());

        assertEquals(northWestEdge, canvas.move(northWestEdge, NativeSkillTreeCanvas.Direction.NORTH_WEST));

        var eastOnly = new NativeSkillTreeCanvas.Point(canvas.minCenterX() + 1, canvas.minCenterY());
        assertEquals(new NativeSkillTreeCanvas.Point(canvas.minCenterX(), canvas.minCenterY()),
                canvas.move(eastOnly, NativeSkillTreeCanvas.Direction.NORTH_WEST));
    }

    @Test
    void selectedViewportAlwaysContainsExactlyNineByFiveCoordinates() {
        NativeSkillTreeCanvas canvas = NativeSkillTreeCanvas.project(tree());
        var center = canvas.start();

        assertEquals(45, canvas.visiblePoints(center).size());
        assertEquals(new NativeSkillTreeCanvas.Point(center.x() - 4, center.y() - 2),
                canvas.visiblePoints(center).getFirst());
        assertEquals(new NativeSkillTreeCanvas.Point(center.x() + 4, center.y() + 2),
                canvas.visiblePoints(center).getLast());
    }

    @Test
    void overlappingConnectorArmsBecomeJunctionOrCrossShapes() {
        assertEquals("16", NativeSkillTreeCanvas.mergeConnectorSuffix("12", "15"));
        assertEquals("20", NativeSkillTreeCanvas.mergeConnectorSuffix("16", "13"));
    }

    private static SkillTree tree() {
        SkillNode root = node("A", 10, SkillRole.MAIN, null);
        SkillNode second = node("B", 30, SkillRole.MAIN, "A");
        SkillNode branch = node("B-1", 30, SkillRole.BRANCH, "B");
        return new SkillTree("MINING", "採掘", "IRON_PICKAXE", "2,10", null,
                Map.of("A", root, "B", second, "B-1", branch));
    }

    private static SkillNode node(String id, int level, SkillRole role, String parent) {
        return new SkillNode(id, id, level, role, parent, null, "STONE", 1,
                "", Map.of(), Map.of(), List.of(), List.of(), List.of());
    }
}
