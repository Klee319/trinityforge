package com.trinityforge.skilltree.generator;

import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTreeLayoutTest {

    private static SkillNode node(String id, int level, SkillRole role, String parent) {
        return node(id, level, role, parent, null);
    }

    private static SkillNode node(String id, int level, SkillRole role, String parent, String group) {
        return new SkillNode(id, id, level, role, parent, group, "STONE", 1,
                null, Map.of(), Map.of(), List.of(), List.of(), List.of());
    }

    private static SkillTree tree(Map<String, SkillNode> nodes) {
        return new SkillTree("TEST", "Test", "STONE", "2,10", null, nodes);
    }

    @Test
    @DisplayName("10 levels map to one inventory row; MAIN lv10 sits one TRUNK_STEP above root")
    void mainUsesTenLevelsPerRow() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("M", node("M", 10, SkillRole.MAIN, null));

        SkillTreeLayout layout = new SkillTreeLayout(tree(nodes));

        assertEquals(1, SkillTreeLayout.rowForLevel(10));
        assertEquals(2, SkillTreeLayout.rowForLevel(20));
        assertEquals(new Coord(2, 10), layout.rootCoord());
        // centerY - 1*TRUNK_STEP = 10 - 2 = 8
        assertEquals(new Coord(2, 8), layout.coordOf("M"));
    }

    @Test
    @DisplayName("adjacent 10-level MAIN rows leave exactly one connector cell between them")
    void adjacentMainsHaveOneConnectorGap() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", 10, SkillRole.MAIN, null));
        nodes.put("B", node("B", 20, SkillRole.MAIN, "A"));

        SkillTreeLayout layout = new SkillTreeLayout(tree(nodes));

        assertEquals(new Coord(2, 8), layout.coordOf("A"));
        assertEquals(new Coord(2, 6), layout.coordOf("B"));
        assertEquals(2, layout.coordOf("A").y() - layout.coordOf("B").y());
    }

    @Test
    @DisplayName("trunk children form distinct branches above their parent (greek stays left)")
    void trunkChildrenFormDistinctBranchesAboveParent() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("M", node("M", 10, SkillRole.MAIN, null));
        nodes.put("G1", node("G1", 10, SkillRole.GREEK, "M"));
        nodes.put("G2", node("G2", 10, SkillRole.GREEK, "M"));

        SkillTreeLayout layout = new SkillTreeLayout(tree(nodes));

        assertEquals(new Coord(2, 8), layout.coordOf("M"));
        assertTrue(layout.coordOf("G1").y() < layout.coordOf("M").y());
        assertTrue(layout.coordOf("G2").y() < layout.coordOf("M").y());
        // 2026-07-30: 排他路線(GREEK)を持つツリーでは GREEK=左半平面 / BRANCH=右半平面 に固定した
        // (以前は「並び順で左右交互」だったため、排他の側が主軸ごとに入れ替わり lv100 帯で分岐
        // チェーンと同じ列に入り込んでコネクタが排他の行を横切っていた)。左右に散らすのではなく
        // 「別セルであること」がこのテストの本旨なので、そちらを検証する。
        assertTrue(layout.coordOf("G1").x() < layout.startX());
        assertTrue(layout.coordOf("G2").x() < layout.startX());
        assertNotEquals(layout.coordOf("G1"), layout.coordOf("G2"));
    }

    @Test
    @DisplayName("exclusive greek roots fan around the common parent instead of forming a chain")
    void exclusiveGreekFork() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("M", node("M", 10, SkillRole.MAIN, null));
        nodes.put("A", node("A", 10, SkillRole.GREEK, "M", "g"));
        nodes.put("B", node("B", 10, SkillRole.GREEK, "M", "g"));
        nodes.put("C", node("C", 10, SkillRole.GREEK, "M", "g"));

        SkillTreeLayout layout = new SkillTreeLayout(tree(nodes));

        assertTrue(layout.coordOf("A").y() < layout.coordOf("M").y());
        assertTrue(layout.coordOf("B").y() < layout.coordOf("M").y());
        assertTrue(layout.coordOf("C").y() < layout.coordOf("M").y());
        assertEquals(3, java.util.Set.of(
                layout.coordOf("A"), layout.coordOf("B"), layout.coordOf("C")).size());
    }

    @Test
    @DisplayName("ungrouped trunk child advances into the next progression layer")
    void trunkBranchUsesNextLayer() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("M", node("M", 10, SkillRole.MAIN, null));
        nodes.put("B", node("B", 20, SkillRole.BRANCH, "M"));

        SkillTreeLayout layout = new SkillTreeLayout(tree(nodes));

        assertEquals(new Coord(2, 8), layout.coordOf("M"));
        assertTrue(layout.coordOf("B").y() < layout.coordOf("M").y());
    }

    @Test
    @DisplayName("branch level-up continues vertically through progression layers")
    void branchLevelUpContinuesUpward() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("M", node("M", 10, SkillRole.MAIN, null));
        nodes.put("B1", node("B1", 10, SkillRole.BRANCH, "M"));
        nodes.put("B2", node("B2", 20, SkillRole.BRANCH, "B1"));

        SkillTreeLayout layout = new SkillTreeLayout(tree(nodes));

        assertTrue(layout.coordOf("B1").y() < layout.coordOf("M").y());
        assertTrue(layout.coordOf("B2").y() < layout.coordOf("B1").y());
    }

    @Test
    @DisplayName("two parents balance fans left and right without sharing cells")
    void twoParentsBalanceSides() {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", 10, SkillRole.MAIN, null));
        nodes.put("B", node("B", 30, SkillRole.MAIN, "A"));
        nodes.put("A1", node("A1", 10, SkillRole.BRANCH, "A"));
        nodes.put("B1", node("B1", 30, SkillRole.BRANCH, "B"));

        SkillTreeLayout layout = new SkillTreeLayout(tree(nodes));

        assertTrue(layout.coordOf("A1").x() > layout.startX());
        assertTrue(layout.coordOf("B1").x() < layout.startX());
    }
}
