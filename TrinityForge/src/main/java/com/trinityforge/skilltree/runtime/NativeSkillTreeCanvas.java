package com.trinityforge.skilltree.runtime;

import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.GeneratedPerk;
import com.trinityforge.skilltree.generator.GeneratedProgression;
import com.trinityforge.skilltree.generator.SkillTreeProgressionGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure 9x5 viewport projection of a canonical TF skill tree. */
final class NativeSkillTreeCanvas {

    private static final int HALF_WIDTH = 4;
    private static final int HALF_HEIGHT = 2;

    private final Map<Point, Cell> cells;
    private final Map<String, NodeCell> nodes;
    private final Point start;
    private final int minCenterX;
    private final int maxCenterX;
    private final int minCenterY;
    private final int maxCenterY;

    private NativeSkillTreeCanvas(Map<Point, Cell> cells, Map<String, NodeCell> nodes,
                                  Point start, int minCenterX, int maxCenterX,
                                  int minCenterY, int maxCenterY) {
        this.cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.start = start;
        this.minCenterX = minCenterX;
        this.maxCenterX = maxCenterX;
        this.minCenterY = minCenterY;
        this.maxCenterY = maxCenterY;
    }

    static NativeSkillTreeCanvas project(SkillTree tree) {
        Objects.requireNonNull(tree, "tree");
        GeneratedProgression generated = SkillTreeProgressionGenerator.generate(tree);
        List<RawCell> rawCells = new ArrayList<>();

        for (GeneratedPerk perk : generated.perks().values()) {
            for (Map<String, Object> segment : perk.connectionLine().values()) {
                Point point = parsePoint(String.valueOf(segment.get("position")));
                String token = String.valueOf(segment.get("locked"));
                rawCells.add(new RawCell(point,
                        new ConnectorCell(point, Set.of(perk.id()), suffix(token))));
            }
            Point point = new Point(perk.coords().x(), perk.coords().y());
            rawCells.add(new RawCell(point, new NodeCell(point, perk.id(), perk)));
        }

        if (rawCells.isEmpty()) {
            throw new IllegalArgumentException("skill tree has no projected cells: " + tree.skill());
        }
        int minX = rawCells.stream().mapToInt(cell -> cell.point.x).min().orElseThrow();
        int maxX = rawCells.stream().mapToInt(cell -> cell.point.x).max().orElseThrow();
        int minY = rawCells.stream().mapToInt(cell -> cell.point.y).min().orElseThrow();
        int maxY = rawCells.stream().mapToInt(cell -> cell.point.y).max().orElseThrow();
        int offsetX = HALF_WIDTH - minX;
        int offsetY = HALF_HEIGHT - minY;
        int width = maxX - minX + 1 + HALF_WIDTH * 2;
        int height = maxY - minY + 1 + HALF_HEIGHT * 2;

        LinkedHashMap<Point, Cell> cells = new LinkedHashMap<>();
        LinkedHashMap<String, NodeCell> nodes = new LinkedHashMap<>();
        for (RawCell raw : rawCells) {
            Point normalized = new Point(raw.point.x + offsetX, raw.point.y + offsetY);
            if (raw.cell instanceof ConnectorCell connector) {
                Cell existing = cells.get(normalized);
                if (existing instanceof ConnectorCell current) {
                    cells.put(normalized, new ConnectorCell(
                            normalized, union(current.ownerPerkIds, connector.ownerPerkIds),
                            mergeConnectorSuffix(current.suffix, connector.suffix)));
                } else {
                    cells.put(normalized,
                            new ConnectorCell(normalized, connector.ownerPerkIds, connector.suffix));
                }
            }
        }
        for (RawCell raw : rawCells) {
            if (raw.cell instanceof NodeCell node) {
                Point normalized = new Point(raw.point.x + offsetX, raw.point.y + offsetY);
                NodeCell normalizedNode = new NodeCell(normalized, node.perkId, node.perk);
                cells.put(normalized, normalizedNode);
                nodes.put(node.perkId, normalizedNode);
            }
        }

        int minCenterX = HALF_WIDTH;
        int maxCenterX = width - HALF_WIDTH - 1;
        int minCenterY = HALF_HEIGHT;
        int maxCenterY = height - HALF_HEIGHT - 1;
        Point configuredStart = parsePoint(generated.startingCoordinates());
        Point start = new Point(
                clamp(configuredStart.x + offsetX, minCenterX, maxCenterX),
                clamp(configuredStart.y + offsetY, minCenterY, maxCenterY));
        return new NativeSkillTreeCanvas(
                cells, nodes, start, minCenterX, maxCenterX, minCenterY, maxCenterY);
    }

    Map<Integer, Cell> viewport(Point center) {
        Point safe = clamp(center);
        LinkedHashMap<Integer, Cell> visible = new LinkedHashMap<>();
        List<Point> points = visiblePoints(safe);
        for (int slot = 0; slot < points.size(); slot++) {
            Cell cell = cells.get(points.get(slot));
            if (cell != null) {
                visible.put(slot, cell);
            }
        }
        return Collections.unmodifiableMap(visible);
    }

    List<Point> visiblePoints(Point center) {
        Point safe = clamp(center);
        List<Point> result = new ArrayList<>(45);
        for (int y = safe.y - HALF_HEIGHT; y <= safe.y + HALF_HEIGHT; y++) {
            for (int x = safe.x - HALF_WIDTH; x <= safe.x + HALF_WIDTH; x++) {
                result.add(new Point(x, y));
            }
        }
        return List.copyOf(result);
    }

    Point move(Point center, Direction direction) {
        Objects.requireNonNull(direction, "direction");
        Point safe = clamp(center);
        return new Point(
                clamp(safe.x + direction.dx, minCenterX, maxCenterX),
                clamp(safe.y + direction.dy, minCenterY, maxCenterY));
    }

    Point clamp(Point center) {
        return new Point(
                clamp(center.x, minCenterX, maxCenterX),
                clamp(center.y, minCenterY, maxCenterY));
    }

    Map<Point, Cell> cells() {
        return cells;
    }

    Map<String, NodeCell> nodes() {
        return nodes;
    }

    Point start() {
        return start;
    }

    int minCenterX() {
        return minCenterX;
    }

    int maxCenterX() {
        return maxCenterX;
    }

    int minCenterY() {
        return minCenterY;
    }

    int maxCenterY() {
        return maxCenterY;
    }

    private static Point parsePoint(String raw) {
        String[] parts = raw.split(",", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid skill-tree coordinate: " + raw);
        }
        try {
            return new Point(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid skill-tree coordinate: " + raw, ex);
        }
    }

    private static String suffix(String token) {
        int separator = token.lastIndexOf(':');
        String number = separator >= 0 ? token.substring(separator + 1) : token;
        if (number.length() < 2) {
            throw new IllegalArgumentException("invalid connector model token: " + token);
        }
        return number.substring(number.length() - 2);
    }

    static String mergeConnectorSuffix(String first, String second) {
        int arms = connectorArms(first) | connectorArms(second);
        return switch (arms) {
            case 0b0011 -> "12";
            case 0b0110 -> "13";
            case 0b1100 -> "14";
            case 0b1001 -> "15";
            case 0b1011 -> "16";
            case 0b0111 -> "17";
            case 0b1110 -> "18";
            case 0b1101 -> "19";
            case 0b1111 -> "20";
            case 0b0101 -> "06";
            case 0b1010 -> "07";
            default -> first;
        };
    }

    private static int connectorArms(String suffix) {
        return switch (suffix) {
            case "00", "06", "10", "11" -> 0b0101;
            case "07", "08", "09" -> 0b1010;
            case "12" -> 0b0011;
            case "13" -> 0b0110;
            case "14" -> 0b1100;
            case "15" -> 0b1001;
            case "16" -> 0b1011;
            case "17" -> 0b0111;
            case "18" -> 0b1110;
            case "19" -> 0b1101;
            case "20" -> 0b1111;
            default -> throw new IllegalArgumentException("unknown connector suffix: " + suffix);
        };
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    sealed interface Cell permits NodeCell, ConnectorCell {
        Point point();
    }

    record NodeCell(Point point, String perkId, GeneratedPerk perk) implements Cell {
    }

    record ConnectorCell(Point point, Set<String> ownerPerkIds, String suffix) implements Cell {
        ConnectorCell {
            ownerPerkIds = Set.copyOf(ownerPerkIds);
        }
    }

    record Point(int x, int y) {
    }

    enum Direction {
        NORTH(0, -1),
        NORTH_EAST(1, -1),
        EAST(1, 0),
        SOUTH_EAST(1, 1),
        SOUTH(0, 1),
        SOUTH_WEST(-1, 1),
        WEST(-1, 0),
        NORTH_WEST(-1, -1);

        private final int dx;
        private final int dy;

        Direction(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }
    }

    private record RawCell(Point point, Cell cell) {
    }
}
