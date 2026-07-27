package com.trinityforge.skilltree.generator;

import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic layered coordinate assignment. MAIN nodes form the central trunk; branch roots
 * alternate around it and every descendant advances one vertical progression layer. Sibling roots
 * are reserved before descendants, preventing a deep route from occupying another route's fork.
 */
public final class SkillTreeLayout {

    /** Inventory-row ↔ skill-level band (ユーザー指定: 1行ごとに10レベル). */
    static final int LEVELS_PER_ROW = 10;
    /**
     * Vertical distance between consecutive MAIN parks (by ordinal). {@code 2} ⇒ connection_line が
     * ちょうど1セル（「主軸のpark間のノードは1本」）。設計密度の目安は {@link #LEVELS_PER_ROW}.
     */
    static final int TRUNK_STEP = 2;
    /** Horizontal distance per branch lane (1 connector cell between adjacent node lanes). */
    static final int SIDE_STEP = 2;
    static final int DEFAULT_START_X = 2;
    static final int DEFAULT_START_Y = 10;
    private static final int MAX_DETOUR = 64;

    private final Map<String, SkillNode> nodes;
    private final int startX;
    private final int centerY;
    private final List<SkillNode> mainsSorted;
    private final Map<String, Integer> mainOrdinal;
    private final Map<String, Coord> memo = new HashMap<>();
    private final Map<Integer, Coord> prestigeCoords = new HashMap<>();
    private final Set<Coord> occupied = new HashSet<>();

    public SkillTreeLayout(SkillTree tree) {
        this.nodes = tree.nodes();
        int[] start = parseCoords(tree.startingCoords());
        this.startX = start[0];
        this.centerY = start[1];
        this.mainsSorted = nodes.values().stream()
                .filter(n -> n.role() == SkillRole.MAIN)
                .sorted(Comparator.comparingInt(SkillNode::level).thenComparing(SkillNode::id))
                .toList();
        Map<String, Integer> ordinal = new HashMap<>();
        for (int i = 0; i < mainsSorted.size(); i++) {
            ordinal.put(mainsSorted.get(i).id(), i);
        }
        this.mainOrdinal = ordinal;
        layoutAll();
        if (tree.prestige() != null && tree.prestige().enabled()) {
            for (int tier = 1; tier <= tree.prestige().maxTimes(); tier++) {
                prestigeCoord(tier);
            }
        }
    }

    public void layoutAll() {
        if (!memo.isEmpty()) return;
        for (int i = 0; i < mainsSorted.size(); i++) {
            put(mainsSorted.get(i).id(),
                    findFreeOnTrunk(new Coord(startX, centerY - (i + 1) * TRUNK_STEP)));
        }
        nodes.values().stream()
                .filter(n -> n.role() == SkillRole.INTERMEDIATE)
                .sorted(Comparator.comparingInt(SkillNode::level).thenComparing(SkillNode::id))
                .forEach(node -> put(node.id(), intermediateCoord(node)));

        Set<String> remaining = new HashSet<>();
        nodes.values().stream()
                .filter(node -> node.role() == SkillRole.BRANCH || node.role() == SkillRole.GREEK)
                .map(SkillNode::id)
                .forEach(remaining::add);
        int trunkFanOrdinal = 0;
        while (!remaining.isEmpty()) {
            List<String> readyParents = remaining.stream()
                    .map(id -> primaryParent(nodes.get(id)))
                    .filter(parent -> parent == null || memo.containsKey(parent) || !nodes.containsKey(parent))
                    .distinct()
                    .sorted(Comparator.nullsFirst(String::compareTo))
                    .toList();
            if (readyParents.isEmpty()) {
                throw new IllegalStateException("parent cycle detected in skill tree");
            }
            for (String parentId : readyParents) {
                List<SkillNode> siblings = remaining.stream()
                        .map(nodes::get)
                        .filter(node -> java.util.Objects.equals(parentId, primaryParent(node)))
                        .sorted(Comparator.comparingInt(SkillNode::level).thenComparing(SkillNode::id))
                        .toList();
                Coord parent = parentId != null && memo.containsKey(parentId)
                        ? memo.get(parentId) : rootCoord();
                boolean trunkParent = parentId == null
                        || (nodes.containsKey(parentId) && isTrunkAttachment(nodes.get(parentId)));
                placeSiblingLayer(siblings, parent, trunkParent, trunkFanOrdinal);
                if (trunkParent && siblings.size() == 1) trunkFanOrdinal++;
                siblings.forEach(node -> remaining.remove(node.id()));
            }
        }
    }

    public int startX() {
        return startX;
    }

    public int centerY() {
        return centerY;
    }

    public Coord rootCoord() {
        return new Coord(startX, centerY);
    }

    public SkillNode topMain() {
        return mainsSorted.isEmpty() ? null : mainsSorted.get(mainsSorted.size() - 1);
    }

    public String startingCoordinates() {
        return startX + "," + centerY;
    }

    /** Reserves a collision-free trunk coordinate for a generated prestige tier. */
    public Coord prestigeCoord(int tier) {
        return prestigeCoords.computeIfAbsent(tier, ignored -> {
            SkillNode top = topMain();
            int topY = top == null ? centerY : coordOf(top.id()).y();
            Coord coord = findFreeOnTrunk(new Coord(startX, topY - tier * TRUNK_STEP));
            occupied.add(coord);
            return coord;
        });
    }

    /** Returns a deterministic Manhattan route including both endpoint nodes and avoiding other nodes. */
    public List<Coord> route(Coord from, Coord to) {
        if (from.equals(to)) return List.of(from);
        int minX = occupied.stream().mapToInt(Coord::x).min().orElse(startX) - 8;
        int maxX = occupied.stream().mapToInt(Coord::x).max().orElse(startX) + 8;
        int minY = occupied.stream().mapToInt(Coord::y).min().orElse(centerY) - 8;
        int maxY = occupied.stream().mapToInt(Coord::y).max().orElse(centerY) + 8;
        ArrayDeque<Coord> queue = new ArrayDeque<>();
        Map<Coord, Coord> previous = new HashMap<>();
        Set<Coord> seen = new HashSet<>();
        queue.add(from);
        seen.add(from);
        int[][] directions = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};
        while (!queue.isEmpty()) {
            Coord current = queue.removeFirst();
            if (current.equals(to)) break;
            for (int[] direction : directions) {
                Coord next = new Coord(current.x() + direction[0], current.y() + direction[1]);
                if (next.x() < minX || next.x() > maxX || next.y() < minY || next.y() > maxY) continue;
                boolean blockedNode = (occupied.contains(next) || rootCoord().equals(next))
                        && !next.equals(from) && !next.equals(to);
                if (blockedNode || !seen.add(next)) continue;
                previous.put(next, current);
                queue.addLast(next);
            }
        }
        if (!seen.contains(to)) {
            throw new IllegalStateException(
                    "no connector route from " + from.format() + " to " + to.format());
        }
        List<Coord> reversed = new ArrayList<>();
        for (Coord cursor = to; cursor != null; cursor = previous.get(cursor)) {
            reversed.add(cursor);
            if (cursor.equals(from)) break;
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    public Coord coordOf(String id) {
        Coord result = memo.get(id);
        if (result == null) {
            throw new IllegalArgumentException("no such node: " + id);
        }
        return result;
    }

    /** Row index for a skill level: lv1–10 → 1, lv11–20 → 2, … */
    static int rowForLevel(int level) {
        return Math.max(1, (Math.max(0, level) + LEVELS_PER_ROW - 1) / LEVELS_PER_ROW);
    }

    private Coord intermediateCoord(SkillNode node) {
        SkillNode lower = null;
        SkillNode upper = null;
        for (SkillNode main : mainsSorted) {
            if (main.level() < node.level()) lower = main;
            if (main.level() > node.level() && upper == null) upper = main;
        }
        int y;
        if (lower != null && upper != null) {
            int lowerY = coordOf(lower.id()).y();
            int upperY = coordOf(upper.id()).y();
            int span = upper.level() - lower.level();
            y = span == 0 ? lowerY
                    : lowerY + (upperY - lowerY) * (node.level() - lower.level()) / span;
        } else if (lower != null) {
            y = coordOf(lower.id()).y() - 1;
        } else if (upper != null) {
            y = coordOf(upper.id()).y() + 1;
        } else {
            y = centerY - TRUNK_STEP;
        }
        return findFreeOnTrunk(new Coord(startX, y));
    }

    private static boolean isTrunkAttachment(SkillNode parent) {
        SkillRole role = parent.role();
        return role == SkillRole.MAIN || role == SkillRole.INTERMEDIATE;
    }

    private void placeSiblingLayer(List<SkillNode> siblings, Coord parent,
                                   boolean trunkParent, int trunkFanOrdinal) {
        int y = parent.y() - TRUNK_STEP;
        for (int i = 0; i < siblings.size(); i++) {
            int offset;
            if (siblings.size() == 1) {
                offset = trunkParent
                        ? (trunkFanOrdinal % 2 == 0 ? SIDE_STEP : -SIDE_STEP)
                        : 0;
            } else {
                int lane = i / 2 + 1;
                offset = (i % 2 == 0 ? -1 : 1) * lane * SIDE_STEP;
            }
            Coord preferred = new Coord(parent.x() + offset, y);
            put(siblings.get(i).id(), findFreeInLayer(preferred, parent));
        }
    }

    private Coord findFreeInLayer(Coord preferred, Coord parent) {
        if (!occupied.contains(preferred) && !preferred.equals(parent)) return preferred;
        for (int ring = 1; ring <= MAX_DETOUR; ring++) {
            Coord left = new Coord(preferred.x() - ring * SIDE_STEP, preferred.y());
            if (!occupied.contains(left) && !left.equals(parent)) return left;
            Coord right = new Coord(preferred.x() + ring * SIDE_STEP, preferred.y());
            if (!occupied.contains(right) && !right.equals(parent)) return right;
        }
        throw new IllegalStateException("no free branch coordinate near " + preferred.format());
    }

    private static String primaryParent(SkillNode node) {
        return node.parent() != null ? node.parent()
                : node.parentsAny().stream().findFirst().orElse(null);
    }

    private void put(String id, Coord coord) {
        occupied.add(coord);
        memo.put(id, coord);
    }

    /** Trunk-only free search (stay on {@code startX}, nudge up if occupied). */
    private Coord findFreeOnTrunk(Coord preferred) {
        if (!occupied.contains(preferred)) {
            return preferred;
        }
        for (int ring = 1; ring <= MAX_DETOUR; ring++) {
            Coord up = new Coord(startX, preferred.y() - ring);
            if (!occupied.contains(up)) {
                return up;
            }
            Coord down = new Coord(startX, preferred.y() + ring);
            if (!occupied.contains(down)) {
                return down;
            }
        }
        throw new IllegalStateException("no free trunk coordinate near " + preferred.format());
    }

    private static int[] parseCoords(String raw) {
        if (raw != null) {
            String[] parts = raw.split(",");
            if (parts.length == 2) {
                try {
                    return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return new int[] {DEFAULT_START_X, DEFAULT_START_Y};
    }
}
