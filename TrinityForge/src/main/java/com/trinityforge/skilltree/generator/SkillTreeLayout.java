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
    /**
     * 空きセル探索で「横に迂回する代わりに上へずらしてよい段数」の上限(2026-07-30)。
     * これ以上は上へ伸ばさず横へ探しに行く(親から縦に離れ過ぎると対応が読めなくなるため)。
     */
    private static final int MAX_DEEPER_ROWS = 2;

    private final Map<String, SkillNode> nodes;
    private final int startX;
    private final int centerY;
    private final List<SkillNode> mainsSorted;
    private final Map<String, Integer> mainOrdinal;
    private final Map<String, Coord> memo = new HashMap<>();
    private final Map<Integer, Coord> prestigeCoords = new HashMap<>();
    private final Set<Coord> occupied = new HashSet<>();
    /**
     * このツリーに排他路線(GREEK)が存在するか(2026-07-30)。存在するなら
     * <b>GREEK は必ず左半平面 / BRANCH は必ず右半平面</b>へ分ける({@link #placeSiblingLayer})。
     * GREEK が1つも無いツリー(ars_magic 等)では従来どおり左右交互に振る — 片側だけに寄せると
     * 画面の半分が空くため。
     */
    private final boolean halfPlaneByRole;

    public SkillTreeLayout(SkillTree tree) {
        this.nodes = tree.nodes();
        int[] start = parseCoords(tree.startingCoords());
        this.startX = start[0];
        this.centerY = start[1];
        this.halfPlaneByRole = tree.nodes().values().stream()
                .anyMatch(node -> node.role() == SkillRole.GREEK);
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
        // 2026-07-29: 探索そのものは GridConnectorRouting へ集約した(アチーブメントGUIと共有)。
        // ここは「何を障害物とみなすか」= 配置済みノード + ルートノード だけを決める。
        Set<Coord> blocked = new HashSet<>(occupied);
        blocked.add(rootCoord());
        return GridConnectorRouting.route(from, to, blocked, 8);
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

    /**
     * 兄弟ノードを親の1段上へ配置する(2026-07-30に排他ノードの干渉解消のため改修)。
     *
     * <p>以前は兄弟を「並び順で左右交互」に振っていたため、排他グループ({@code group})のメンバーが
     * 通常の分岐ノードや主軸を挟んで左右に散り、「排他の選択肢がどれとどれなのか分からない/ノードが
     * くっつく」状態になっていた(例: light_weapons の C は分岐 C-1-1 と排他3兄弟が
     * 左・右・左2・右2 と交互に並び、主軸 D が排他の間に入っていた)。
     *
     * <p>改修後は<b>グループ単位でバケットに束ね、1バケットは必ず片側の連続レーンを占める</b>。
     * バケットの並びは左→右→左…の順(既存の左右バランスの期待値を保つ)で、レーン番号は
     * 側ごとの通し番号なので、異なるバケットのノードが同じレーンへ食い込むことがない。
     *
     * <p>兄弟が1つだけの場合は従来どおり(主軸の子は {@code trunkFanOrdinal} で左右交互、
     * それ以外は真上)。
     *
     * <p><b>2026-07-30 追加改修(排他×分岐の干渉)</b>: バケットの側を「並び順」で決めていたため、
     * 排他グループの側が<em>その主軸に分岐の子が居るかどうか</em>で入れ替わっていた
     * (light/heavy_weapons: C・D は分岐がバケット0=左を取るので排他が右、分岐を持たない E は
     * 排他がバケット0=左)。その結果 lv90〜100 帯で分岐チェーンの列(x=0)と E の排他が同じ列に来て、
     * 分岐のコネクタが排他グループの行を横切っていた。排他を持つツリーでは
     * <b>GREEK=左 / BRANCH=右</b>に固定して半平面を分離する({@code docs/agent-context/
     * progression-skilltree.md} が元々「BRANCH は右半平面・GREEK は左半平面」と書いていた設計意図)。
     */
    private void placeSiblingLayer(List<SkillNode> siblings, Coord parent,
                                   boolean trunkParent, int trunkFanOrdinal) {
        int y = parent.y() - TRUNK_STEP;
        if (siblings.size() == 1) {
            SkillNode only = siblings.get(0);
            int offset;
            if (halfPlaneByRole && trunkParent) {
                offset = sideSign(only) * SIDE_STEP;
            } else {
                offset = trunkParent ? (trunkFanOrdinal % 2 == 0 ? SIDE_STEP : -SIDE_STEP) : 0;
            }
            put(only.id(), findFreeInLayer(new Coord(parent.x() + offset, y), parent));
            return;
        }
        List<List<SkillNode>> buckets = groupBuckets(siblings);
        int[] lanesPerSide = new int[2]; // 0 = 左(-X), 1 = 右(+X)
        for (int bucketIndex = 0; bucketIndex < buckets.size(); bucketIndex++) {
            List<SkillNode> bucket = buckets.get(bucketIndex);
            int sign = halfPlaneByRole ? sideSign(bucket.get(0)) : (bucketIndex % 2 == 0 ? -1 : 1);
            int side = sign < 0 ? 0 : 1;
            for (SkillNode member : bucket) {
                int lane = ++lanesPerSide[side];
                Coord preferred = new Coord(parent.x() + sign * lane * SIDE_STEP, y);
                put(member.id(), findFreeInLayer(preferred, parent));
            }
        }
    }

    /** 半平面分離の向き: GREEK(排他路線)は左(-1)、それ以外(BRANCH)は右(+1)。 */
    private static int sideSign(SkillNode node) {
        return node.role() == SkillRole.GREEK ? -1 : 1;
    }

    /**
     * 兄弟を「同じ {@code group} は1バケット / グループ無しは1ノードで1バケット」へ束ねる。
     * バケットの順序は引数の並び(level→id)における初出順なので、配置は決定的。
     */
    private static List<List<SkillNode>> groupBuckets(List<SkillNode> siblings) {
        Map<String, List<SkillNode>> buckets = new java.util.LinkedHashMap<>();
        for (int i = 0; i < siblings.size(); i++) {
            SkillNode node = siblings.get(i);
            String key = node.group() != null && !node.group().isBlank()
                    ? "group:" + node.group()
                    : "solo:" + i + ":" + node.id();
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node);
        }
        return List.copyOf(buckets.values());
    }

    /**
     * 分岐ノードの空きセル探索。
     *
     * <p>2026-07-30: 以前は「同じ行を横へ延々と探す」だけだったため、
     * (1) 目的地が埋まっていると4セル以上横へ飛んで親との対応が読めなくなる、
     * (2) 主軸の列({@link #startX})へ着地して主軸ノード/プレステージと隣接し「くっついて見える」、
     * という2つの崩れ方をしていた。改修後は
     * <ul>
     *   <li>コスト(=横のレーン数 + 上へずらす段数)の小さい順に探し、同コストなら<b>上へ深く</b>を
     *       優先する(横に大きく迂回する前に1段上へ逃げる)</li>
     *   <li>主軸の列を分岐ノードに使わせない(主軸/プレステージ専用に予約)</li>
     *   <li>既存ノードと8近傍で隣接するセルを避ける(必ずコネクタ1セル分の隙間を残す)</li>
     * </ul>
     * を満たす最初のセルを返す。
     *
     * <p>どうしても見つからない場合は「迂回なしコネクタ」→ 隣接禁止 → 主軸列予約の順に条件を
     * 緩めるので、「配置できずに例外」という結果が改修前より増えることはない。
     */
    private Coord findFreeInLayer(Coord preferred, Coord parent) {
        Coord strict = search(preferred, parent, true, true, true);
        if (strict != null) return strict;
        Coord relaxedRoute = search(preferred, parent, true, true, false);
        if (relaxedRoute != null) return relaxedRoute;
        Coord relaxedAdjacency = search(preferred, parent, false, true, false);
        if (relaxedAdjacency != null) return relaxedAdjacency;
        Coord anyFree = search(preferred, parent, false, false, false);
        if (anyFree != null) return anyFree;
        throw new IllegalStateException("no free branch coordinate near " + preferred.format());
    }

    private Coord search(Coord preferred, Coord parent, boolean requireGap, boolean avoidTrunkColumn,
                         boolean requireDirectRoute) {
        for (int cost = 0; cost <= MAX_DETOUR; cost++) {
            for (int depth = Math.min(cost, MAX_DEEPER_ROWS); depth >= 0; depth--) {
                int ring = cost - depth;
                int y = preferred.y() - depth * TRUNK_STEP;
                if (ring == 0) {
                    Coord center = new Coord(preferred.x(), y);
                    if (fits(center, parent, requireGap, avoidTrunkColumn, requireDirectRoute)) return center;
                    continue;
                }
                Coord left = new Coord(preferred.x() - ring * SIDE_STEP, y);
                if (fits(left, parent, requireGap, avoidTrunkColumn, requireDirectRoute)) return left;
                Coord right = new Coord(preferred.x() + ring * SIDE_STEP, y);
                if (fits(right, parent, requireGap, avoidTrunkColumn, requireDirectRoute)) return right;
            }
        }
        return null;
    }

    private boolean fits(Coord candidate, Coord parent, boolean requireGap, boolean avoidTrunkColumn,
                         boolean requireDirectRoute) {
        if (occupied.contains(candidate) || candidate.equals(parent) || candidate.equals(rootCoord())) {
            return false;
        }
        if (avoidTrunkColumn && candidate.x() == startX) {
            return false;
        }
        if (requireGap && touchesPlacedNode(candidate)) {
            return false;
        }
        return !requireDirectRoute || hasDirectRoute(parent, candidate);
    }

    /**
     * 親からこのセルまで<b>迂回なし</b>でコネクタを引けるか(2026-07-30)。
     *
     * <p>空きセル判定だけでは「親との間に別のノードが挟まっている」座標を弾けない。実例:
     * light_weapons / heavy_weapons の lv100 で {@code D-1-2} が親 {@code D-1-1(0,0)} の真上2段
     * {@code (0,-4)} に置かれ、その間の {@code (0,-2)} に排他ノード {@code E-alpha-1} が居た。
     * 空きセルとしては合格するがコネクタは通れず、{@link #route} が排他グループの行を大きく
     * 横切る迂回路を引いて「排他と分岐が干渉している」状態になっていた。
     *
     * <p>{@link GridConnectorRouting#route} は BFS なので、障害物が無ければ経路長は必ず
     * マンハッタン距離に一致する。<b>経路長 &gt; マンハッタン距離 = 迂回が必要</b>なので、
     * これを候補の足切りに使う(階段状の経路は迂回ではないので通る)。
     */
    private boolean hasDirectRoute(Coord parent, Coord candidate) {
        int manhattan = Math.abs(parent.x() - candidate.x()) + Math.abs(parent.y() - candidate.y());
        Set<Coord> blocked = new HashSet<>(occupied);
        blocked.add(rootCoord());
        try {
            return GridConnectorRouting.route(parent, candidate, blocked, 2).size() - 1 == manhattan;
        } catch (IllegalStateException unreachable) {
            return false;
        }
    }

    /** 8近傍(斜めを含む)に配置済みノードがあるか = コネクタ用の隙間が無いか。 */
    private boolean touchesPlacedNode(Coord candidate) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                if (occupied.contains(new Coord(candidate.x() + dx, candidate.y() + dy))) {
                    return true;
                }
            }
        }
        return false;
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
