package com.trinityforge.skilltree.generator;

import com.trinityforge.config.domains.SkillTreeConfig;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillTree;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 総合ツリーの実配置を ASCII と合成コネクタ形状で出す。
 * 実機スクショ「縦3列が横線で格子状につながる」が、現行配置でも旧レーン配りでも出るかを見る。
 */
class PowerSkillTreeConnectorDumpTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("PowerSkillTreeConnectorDumpTest");
            case "saveResource" -> throw new AssertionError("saveResource() must not run headlessly");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static SkillTree loadPower(File dataFolder) throws IOException {
        File dir = new File(dataFolder, SkillTreeConfig.DIR);
        Files.createDirectories(dir.toPath());
        try (InputStream in = PowerSkillTreeConnectorDumpTest.class.getClassLoader()
                .getResourceAsStream(SkillTreeConfig.DIR + "/power.yml")) {
            assertNotNull(in, "bundled skilltree/power.yml must be on the test classpath");
            Files.copy(in, new File(dir, "power.yml").toPath());
        }
        SkillTreeConfig config = new SkillTreeConfig();
        config.load(fakePlugin(dataFolder));
        SkillTree tree = config.tree("POWER").orElseThrow();
        return tree;
    }

    @Test
    @DisplayName("総合の現行配置は、別親のコネクタをT字・十字へ合成しない")
    void currentPowerLayoutDoesNotCrossUnrelatedConnectors(@TempDir File dataFolder) throws IOException {
        SkillTree tree = loadPower(dataFolder);
        String current = dump(tree, true, true);

        List<String> currentCross = unrelatedJunctions(tree, true, true);
        assertTrue(currentCross.isEmpty(),
                () -> "現行配置でも無関係な線がT/十字に合成されている:\n  "
                        + String.join("\n  ", currentCross)
                        + "\n" + current);

        SkillTreeLayout layout = new SkillTreeLayout(tree);
        List<Coord> a1Path = layout.route(layout.coordOf("A"), layout.coordOf("A-1"));
        assertTrue(a1Path.stream().noneMatch(cell -> cell.x() == 3 && cell.y() == 6),
                () -> "A→A-1 がノード行の隙間 (3,6) を通っている: " + a1Path
                        + "\n" + current);
    }

    private static String dump(SkillTree tree, boolean rowFirst, boolean continuationFirst) {
        SkillTreeLayout layout = new SkillTreeLayout(tree, rowFirst, continuationFirst);
        Map<String, Coord> coords = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (SkillNode node : tree.nodes().values()) {
            Coord c = layout.coordOf(node.id());
            coords.put(node.id(), c);
            minX = Math.min(minX, c.x());
            maxX = Math.max(maxX, c.x());
            minY = Math.min(minY, c.y());
            maxY = Math.max(maxY, c.y());
        }
        Map<Coord, String> merged = mergedSuffixes(tree, layout);
        StringBuilder out = new StringBuilder();
        List<String> ids = new ArrayList<>(coords.keySet());
        ids.sort((a, b) -> {
            Coord ca = coords.get(a);
            Coord cb = coords.get(b);
            int byY = Integer.compare(cb.y(), ca.y());
            return byY != 0 ? byY : Integer.compare(ca.x(), cb.x());
        });
        for (String id : ids) {
            SkillNode node = tree.nodes().get(id);
            out.append(String.format("%-14s %-8s %s parent=%s%n",
                    id, node.role(), coords.get(id).format(), node.parent()));
        }
        out.append('\n');
        Map<String, String> idAt = new TreeMap<>();
        coords.forEach((id, c) -> idAt.put(c.format(), id));
        for (int y = maxY; y >= minY; y--) {
            out.append(String.format("y=%2d |", y));
            for (int x = minX; x <= maxX; x++) {
                Coord cell = new Coord(x, y);
                String id = idAt.get(cell.format());
                if (id != null) {
                    out.append(shortId(id));
                } else {
                    String suffix = merged.get(cell);
                    out.append(suffix == null ? "  . " : shape(suffix));
                }
            }
            out.append('\n');
        }
        out.append("unrelated T/+: ").append(unrelatedJunctions(tree, rowFirst, continuationFirst));
        return out.toString();
    }

    private static List<String> unrelatedJunctions(SkillTree tree, boolean rowFirst,
                                                   boolean continuationFirst) {
        SkillTreeLayout layout = new SkillTreeLayout(tree, rowFirst, continuationFirst);
        Map<Coord, List<String>> owners = new LinkedHashMap<>();
        Map<Coord, String> merged = new LinkedHashMap<>();
        for (SkillNode node : tree.nodes().values()) {
            String parentId = node.parent();
            if (parentId == null || parentId.isBlank() || !tree.nodes().containsKey(parentId)) {
                continue;
            }
            Coord parent = layout.coordOf(parentId);
            Coord child = layout.coordOf(node.id());
            List<Coord> path;
            try {
                path = layout.route(parent, child);
            } catch (RuntimeException ignored) {
                continue;
            }
            String edge = parentId + "->" + node.id();
            for (int i = 1; i < path.size() - 1; i++) {
                Coord cell = path.get(i);
                String suffix = GridConnectorRouting.suffix(
                        cell, path.get(i - 1), path.get(i + 1), i - 1 == 0, i + 1 == path.size() - 1);
                List<String> cellOwners =
                        owners.computeIfAbsent(cell, ignored -> new ArrayList<>());
                cellOwners.add(edge);
                merged.merge(cell, suffix, (first, second) -> GridConnectorRouting.merge(
                        first, second, sameFan(tree, cellOwners)));
            }
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Coord, String> entry : merged.entrySet()) {
            String suffix = entry.getValue();
            if (!isJunction(suffix)) {
                continue;
            }
            List<String> edges = owners.get(entry.getKey());
            if (edges == null || edges.size() < 2) {
                continue;
            }
            if (sameFan(tree, edges)) {
                continue;
            }
            problems.add(entry.getKey().format() + " suffix=" + suffix + " " + edges);
        }
        return problems;
    }

    /** 同じ親から出た扇なら、横線がT字になるのは正しい。 */
    private static boolean sameFan(SkillTree tree, List<String> edges) {
        String sharedParent = null;
        for (String edge : edges) {
            String parent = edge.substring(0, edge.indexOf("->"));
            if (sharedParent == null) {
                sharedParent = parent;
            } else if (!sharedParent.equals(parent)) {
                return false;
            }
        }
        return sharedParent != null;
    }

    private static Map<Coord, String> mergedSuffixes(SkillTree tree, SkillTreeLayout layout) {
        Map<Coord, String> merged = new LinkedHashMap<>();
        Map<Coord, List<String>> owners = new LinkedHashMap<>();
        for (SkillNode node : tree.nodes().values()) {
            String parentId = node.parent();
            if (parentId == null || parentId.isBlank() || !tree.nodes().containsKey(parentId)) {
                continue;
            }
            List<Coord> path;
            try {
                path = layout.route(layout.coordOf(parentId), layout.coordOf(node.id()));
            } catch (RuntimeException ignored) {
                continue;
            }
            for (int i = 1; i < path.size() - 1; i++) {
                Coord cell = path.get(i);
                String suffix = GridConnectorRouting.suffix(
                        cell, path.get(i - 1), path.get(i + 1), i - 1 == 0, i + 1 == path.size() - 1);
                List<String> cellOwners =
                        owners.computeIfAbsent(cell, ignored -> new ArrayList<>());
                cellOwners.add(parentId + "->" + node.id());
                merged.merge(cell, suffix, (first, second) -> GridConnectorRouting.merge(
                        first, second, sameFan(tree, cellOwners)));
            }
        }
        return merged;
    }

    private static boolean isJunction(String suffix) {
        return switch (suffix) {
            case "16", "17", "18", "19", "20" -> true;
            default -> false;
        };
    }

    private static String shortId(String id) {
        String raw = switch (id) {
            case "A-alpha-1" -> "αA";
            case "B-alpha-1" -> "αB";
            case "C-alpha-1" -> "αC";
            case "D-alpha-1" -> "αD";
            case "E-alpha-1" -> "αE";
            case "A-beta-1" -> "βA";
            case "B-beta-1" -> "βB";
            case "C-beta-1" -> "βC";
            case "D-beta-1" -> "βD";
            case "E-beta-1" -> "βE";
            case "A-gamma-1" -> "γA";
            case "B-gamma-1" -> "γB";
            case "C-gamma-1" -> "γC";
            case "D-gamma-1" -> "γD";
            case "E-gamma-1" -> "γE";
            default -> id.length() <= 3 ? id : id.substring(0, 3);
        };
        return String.format("[%2s]", raw);
    }

    private static String shape(String suffix) {
        return switch (suffix) {
            case "00", "06", "10", "11" -> "  | ";
            case "07", "08", "09" -> "  - ";
            case "12" -> "  └ ";
            case "13" -> "  ┌ ";
            case "14" -> "  ┐ ";
            case "15" -> "  ┘ ";
            case "16" -> "  ┴ ";
            case "17" -> "  ├ ";
            case "18" -> "  ┬ ";
            case "19" -> "  ┤ ";
            case "20" -> "  + ";
            default -> "  ? ";
        };
    }
}
