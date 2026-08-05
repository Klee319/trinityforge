package com.trinityforge.progression.achievement;

import com.trinityforge.config.domains.AchievementsConfig.Achievement;
import com.trinityforge.skilltree.generator.Coord;
import com.trinityforge.skilltree.generator.GridConnectorRouting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code /achievement} GUI のための、アチーブメント・ツリーの純粋な格子投影。
 *
 * <p>スキルツリーGUI({@code NativeSkillTreeCanvas})と同じ 9x5 ビューポート・同じコネクタ形状
 * ({@link GridConnectorRouting})を使う。<b>座標の決め方だけが違う</b>: スキルツリーは
 * レベル帯から機械的に決まるが、アチーブメントは {@code coords} を書けば固定、書かなければ
 * 前提関係から自動配置する。
 *
 * <p>Bukkit に依存しないのでヘッドレスに単体テストできる。
 */
public final class AchievementCanvas {

    /** ビューポートの半幅/半高 (9x5 = 45 スロット)。 */
    private static final int HALF_WIDTH = 4;
    private static final int HALF_HEIGHT = 2;
    /** 自動配置の列/行の刻み。奇数セルをコネクタ用に空けるため 2。 */
    private static final int STEP = 2;

    private final Map<Point, Cell> cells;
    private final Map<String, NodeCell> nodes;
    private final Point start;
    private final int minCenterX;
    private final int maxCenterX;
    private final int minCenterY;
    private final int maxCenterY;

    private AchievementCanvas(Map<Point, Cell> cells, Map<String, NodeCell> nodes, Point start,
                              int minCenterX, int maxCenterX, int minCenterY, int maxCenterY) {
        this.cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.start = start;
        this.minCenterX = minCenterX;
        this.maxCenterX = maxCenterX;
        this.minCenterY = minCenterY;
        this.maxCenterY = maxCenterY;
    }

    /**
     * @param achievements 表示対象(読み込み順。{@code coords} 未指定分はこの順で自動配置する)
     * @throws IllegalArgumentException 1件も無いとき
     */
    public static AchievementCanvas project(List<Achievement> achievements) {
        Objects.requireNonNull(achievements, "achievements");
        if (achievements.isEmpty()) {
            throw new IllegalArgumentException("no achievements to project");
        }
        Map<String, Coord> coords = AchievementLayout.assign(achievements);

        // --- ノードセル ---
        LinkedHashMap<String, NodeCell> rawNodes = new LinkedHashMap<>();
        for (Achievement achievement : achievements) {
            Coord coord = coords.get(achievement.id());
            rawNodes.put(achievement.id(), new NodeCell(new Point(coord.x(), coord.y()), achievement));
        }
        Set<Coord> occupied = new HashSet<>(coords.values());

        // --- コネクタセル (親→子。parents-any も同じ見た目で引く) ---
        LinkedHashMap<Point, ConnectorCell> connectors = new LinkedHashMap<>();
        for (Achievement achievement : achievements) {
            for (String parentId : prerequisiteIds(achievement)) {
                Coord parent = coords.get(parentId);
                if (parent == null) {
                    continue; // 存在しない前提。読み込み時に警告済みなので描画では黙って飛ばす。
                }
                addConnectors(connectors, parent, coords.get(achievement.id()), occupied, achievement.id());
            }
        }

        // --- 端に余白(半ビューポート分)を足して正規化 ---
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (NodeCell node : rawNodes.values()) {
            minX = Math.min(minX, node.point.x);
            maxX = Math.max(maxX, node.point.x);
            minY = Math.min(minY, node.point.y);
            maxY = Math.max(maxY, node.point.y);
        }
        for (Point point : connectors.keySet()) {
            minX = Math.min(minX, point.x);
            maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
        }
        int offsetX = HALF_WIDTH - minX;
        int offsetY = HALF_HEIGHT - minY;
        int width = maxX - minX + 1 + HALF_WIDTH * 2;
        int height = maxY - minY + 1 + HALF_HEIGHT * 2;

        LinkedHashMap<Point, Cell> placed = new LinkedHashMap<>();
        for (Map.Entry<Point, ConnectorCell> entry : connectors.entrySet()) {
            Point shifted = new Point(entry.getKey().x + offsetX, entry.getKey().y + offsetY);
            ConnectorCell value = entry.getValue();
            placed.put(shifted, new ConnectorCell(shifted, value.ownerIds, value.suffix));
        }
        LinkedHashMap<String, NodeCell> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, NodeCell> entry : rawNodes.entrySet()) {
            NodeCell value = entry.getValue();
            Point shifted = new Point(value.point.x + offsetX, value.point.y + offsetY);
            NodeCell moved = new NodeCell(shifted, value.achievement);
            placed.put(shifted, moved); // ノードはコネクタより優先(同じセルなら上書き)
            nodes.put(entry.getKey(), moved);
        }

        int minCenterX = HALF_WIDTH;
        int maxCenterX = Math.max(minCenterX, width - HALF_WIDTH - 1);
        int minCenterY = HALF_HEIGHT;
        int maxCenterY = Math.max(minCenterY, height - HALF_HEIGHT - 1);
        // 起点は最初のノード(=最初の根)が中央に来る位置。
        NodeCell first = nodes.values().iterator().next();
        Point start = new Point(
                clamp(first.point.x, minCenterX, maxCenterX),
                clamp(first.point.y, minCenterY, maxCenterY));
        return new AchievementCanvas(placed, nodes, start,
                minCenterX, maxCenterX, minCenterY, maxCenterY);
    }

    /**
     * 最下段の系統切替バー(2026-08-06, W-31)に並べる「系統の起点」を、設定順で返す。
     *
     * <p>内訳は2種類:
     * <ol>
     *   <li>前提を1つも持たないアチーブメント(＝真のルート)</li>
     *   <li>そのルートが2つ以上の子を持つ場合は、その子(＝そこから分かれる各系統の先頭)も</li>
     * </ol>
     *
     * <p><b>2 を含めるのは出荷configの形のため</b>: 真のルートは {@code main} の1件だけで、
     * その下に5章がぶら下がっている。ルートだけを並べるとバーがボタン1個になり
     * 「最下段でルート実績をスクロール切替」が成立しない。ルートが複数あるconfigでは
     * そのまま全ルートが並ぶ(どちらの形でも壊れない)。
     *
     * <p>親子関係は自動配置と同じく {@code parent} 鎖だけを見る({@code parents-any} は
     * 線としてだけ描かれる関係なので、系統の所属を決める根拠にしない)。
     */
    public static List<String> branchHeadIds(List<Achievement> achievements) {
        Objects.requireNonNull(achievements, "achievements");
        Set<String> known = new HashSet<>();
        for (Achievement achievement : achievements) {
            known.add(achievement.id());
        }
        LinkedHashMap<String, List<String>> childrenOf = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (Achievement achievement : achievements) {
            String parent = achievement.parent();
            if (parent != null && known.contains(parent) && !parent.equals(achievement.id())) {
                childrenOf.computeIfAbsent(parent, key -> new ArrayList<>()).add(achievement.id());
            } else {
                roots.add(achievement.id());
            }
        }
        LinkedHashSet<String> heads = new LinkedHashSet<>();
        for (String root : roots) {
            heads.add(root);
            List<String> kids = childrenOf.getOrDefault(root, List.of());
            if (kids.size() >= 2) {
                heads.addAll(kids);
            }
        }
        return List.copyOf(heads);
    }

    /** {@code parent} と {@code parents-any} を1つの前提IDリストへ(重複は畳む)。 */
    static List<String> prerequisiteIds(Achievement achievement) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (achievement.parent() != null) {
            ids.add(achievement.parent());
        }
        ids.addAll(achievement.parentsAny());
        return List.copyOf(ids);
    }

    private static void addConnectors(Map<Point, ConnectorCell> out, Coord parent, Coord child,
                                      Set<Coord> occupied, String ownerId) {
        List<Coord> path;
        try {
            path = GridConnectorRouting.route(parent, child, occupied, 4);
        } catch (RuntimeException ex) {
            return; // 迂回できない配置。線を諦めてもノード自体は出す(GUI全体を落とさない)。
        }
        for (int i = 1; i < path.size() - 1; i++) {
            Coord cell = path.get(i);
            String suffix = GridConnectorRouting.suffix(
                    cell, path.get(i - 1), path.get(i + 1), (i - 1) == 0, (i + 1) == path.size() - 1);
            Point point = new Point(cell.x(), cell.y());
            ConnectorCell existing = out.get(point);
            if (existing == null) {
                out.put(point, new ConnectorCell(point, Set.of(ownerId), suffix));
            } else {
                LinkedHashSet<String> owners = new LinkedHashSet<>(existing.ownerIds);
                owners.add(ownerId);
                out.put(point, new ConnectorCell(point, Set.copyOf(owners),
                        GridConnectorRouting.merge(existing.suffix, suffix)));
            }
        }
    }

    /** 中心 {@code center} を基準にした 9x5 ビューポート(スロット番号 → セル)。 */
    public Map<Integer, Cell> viewport(Point center) {
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

    public Point move(Point center, int dx, int dy) {
        Point safe = clamp(center);
        return new Point(clamp(safe.x + dx, minCenterX, maxCenterX),
                clamp(safe.y + dy, minCenterY, maxCenterY));
    }

    public Point clamp(Point center) {
        return new Point(clamp(center.x, minCenterX, maxCenterX), clamp(center.y, minCenterY, maxCenterY));
    }

    public Point start() {
        return start;
    }

    public Map<String, NodeCell> nodes() {
        return nodes;
    }

    /** そのIDのノードが中央に来る視点(存在しなければ {@link #start()})。 */
    public Point focusOn(String achievementId) {
        NodeCell node = nodes.get(achievementId);
        return node == null ? start : clamp(node.point);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /** 自動配置。{@code coords} が書かれているものは尊重し、残りを前提関係から並べる。 */
    static final class AchievementLayout {

        private AchievementLayout() {
        }

        static Map<String, Coord> assign(List<Achievement> achievements) {
            LinkedHashMap<String, Coord> result = new LinkedHashMap<>();
            Set<Coord> taken = new HashSet<>();
            for (Achievement achievement : achievements) {
                Coord explicit = parseCoords(achievement.coords());
                if (explicit != null && taken.add(explicit)) {
                    result.put(achievement.id(), explicit);
                }
            }
            // 子リストを作る(自動配置は parent 鎖だけを見る。parents-any は線としてだけ描く)。
            Map<String, List<Achievement>> children = new LinkedHashMap<>();
            List<Achievement> roots = new ArrayList<>();
            Set<String> known = new HashSet<>();
            for (Achievement achievement : achievements) {
                known.add(achievement.id());
            }
            for (Achievement achievement : achievements) {
                String parent = achievement.parent();
                if (parent != null && known.contains(parent) && !parent.equals(achievement.id())) {
                    children.computeIfAbsent(parent, key -> new ArrayList<>()).add(achievement);
                } else {
                    roots.add(achievement);
                }
            }
            int[] nextColumn = {0};
            Set<String> visiting = new HashSet<>();
            for (Achievement root : roots) {
                place(root, 0, children, result, taken, nextColumn, visiting);
            }
            // 循環で置けなかったものは末尾へ流す(GUIから消えないようにする)。
            for (Achievement achievement : achievements) {
                if (!result.containsKey(achievement.id())) {
                    result.put(achievement.id(), freeCoord(nextColumn, 0, taken));
                }
            }
            return Map.copyOf(result);
        }

        private static Coord place(Achievement node, int depth, Map<String, List<Achievement>> children,
                                   Map<String, Coord> result, Set<Coord> taken, int[] nextColumn,
                                   Set<String> visiting) {
            if (!visiting.add(node.id())) {
                return result.get(node.id()); // 循環: 二度目は置かない
            }
            List<Achievement> kids = children.getOrDefault(node.id(), List.of());
            List<Coord> kidCoords = new ArrayList<>();
            for (Achievement kid : kids) {
                Coord placed = place(kid, depth + 1, children, result, taken, nextColumn, visiting);
                if (placed != null) {
                    kidCoords.add(placed);
                }
            }
            Coord existing = result.get(node.id());
            if (existing != null) {
                return existing; // coords 明示済み
            }
            // 2026-08-06(W-31): 深いノードほど y を小さくする＝<b>ルートから上へ伸ばす</b>。
            // スキルツリー({@code SkillTreeLayout})が主軸を上へ伸ばすのに合わせた
            // (同じ操作系のGUIで伸びる向きだけ逆だと、8方向パッドの上下感覚が入れ替わる)。
            // y の意味自体は変えていない(下方向が正)ので、明示 coords はそのままの読み方で効く。
            int y = -depth * STEP;
            int x;
            if (kidCoords.isEmpty()) {
                x = nextColumn[0];
                nextColumn[0] += STEP;
            } else {
                int minX = kidCoords.stream().mapToInt(Coord::x).min().orElse(0);
                int maxX = kidCoords.stream().mapToInt(Coord::x).max().orElse(0);
                x = ((minX + maxX) / 2 / STEP) * STEP; // 偶数列に丸める(奇数列はコネクタ用)
            }
            Coord coord = new Coord(x, y);
            while (!taken.add(coord)) {
                x += STEP;
                coord = new Coord(x, y);
            }
            nextColumn[0] = Math.max(nextColumn[0], x + STEP);
            result.put(node.id(), coord);
            return coord;
        }

        private static Coord freeCoord(int[] nextColumn, int y, Set<Coord> taken) {
            Coord coord = new Coord(nextColumn[0], y);
            while (!taken.add(coord)) {
                nextColumn[0] += STEP;
                coord = new Coord(nextColumn[0], y);
            }
            nextColumn[0] += STEP;
            return coord;
        }

        /** {@code "x,y"} を読む。空欄/壊れた記法は null(＝自動配置に回す)。 */
        static Coord parseCoords(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] parts = raw.split(",", -1);
            if (parts.length != 2) {
                return null;
            }
            try {
                return new Coord(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    /** 格子上の1セル。 */
    public sealed interface Cell permits NodeCell, ConnectorCell {
        Point point();
    }

    /** アチーブメント本体が乗るセル。 */
    public record NodeCell(Point point, Achievement achievement) implements Cell {
    }

    /**
     * ノード間をつなぐ線のセル。
     *
     * @param ownerIds この線がどのアチーブメントへ向かうものか(色を決めるのに使う)
     * @param suffix   コネクタ形状の2桁コード({@link GridConnectorRouting})
     */
    public record ConnectorCell(Point point, Set<String> ownerIds, String suffix) implements Cell {
        public ConnectorCell {
            ownerIds = Set.copyOf(ownerIds);
        }
    }

    /** 格子座標(y は下方向に増える)。 */
    public record Point(int x, int y) {
    }
}
