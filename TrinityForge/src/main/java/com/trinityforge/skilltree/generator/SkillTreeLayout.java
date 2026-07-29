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
     */
    private void placeSiblingLayer(List<SkillNode> siblings, Coord parent,
                                   boolean trunkParent, int trunkFanOrdinal) {
        int y = parent.y() - TRUNK_STEP;
        if (siblings.size() == 1) {
            int offset = trunkParent
                    ? (trunkFanOrdinal % 2 == 0 ? SIDE_STEP : -SIDE_STEP)
                    : 0;
            put(siblings.get(0).id(), findFreeInLayer(new Coord(parent.x() + offset, y), parent));
            return;
        }
        List<List<SkillNode>> buckets = groupBuckets(siblings);
        int[] lanesPerSide = new int[2]; // 0 = 左(-X), 1 = 右(+X)
        for (int bucketIndex = 0; bucketIndex < buckets.size(); bucketIndex++) {
            int side = bucketIndex % 2;
            int sign = side == 0 ? -1 : 1;
            for (SkillNode member : buckets.get(bucketIndex)) {
                int lane = ++lanesPerSide[side];
                Coord preferred = new Coord(parent.x() + sign * lane * SIDE_STEP, y);
                put(member.id(), findFreeInLayer(preferred, parent));
            }
        }
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
     * <p>どうしても見つからない場合は隣接禁止 → 主軸列予約の順に条件を緩めるので、
     * 「配置できずに例外」という結果が改修前より増えることはない。
     */
    private Coord findFreeInLayer(Coord preferred, Coord parent) {
        Coord strict = search(preferred, parent, true, true);
        if (strict != null) return strict;
        Coord relaxedAdjacency = search(preferred, parent, false, true);
        if (relaxedAdjacency != null) return relaxedAdjacency;
        Coord anyFree = search(preferred, parent, false, false);
        if (anyFree != null) return anyFree;
        throw new IllegalStateException("no free branch coordinate near " + preferred.format());
    }

    private Coord search(Coord preferred, Coord parent, boolean requireGap, boolean avoidTrunkColumn) {
        for (int cost = 0; cost <= MAX_DETOUR; cost++) {
            for (int depth = Math.min(cost, MAX_DEEPER_ROWS); depth >= 0; depth--) {
                int ring = cost - depth;
                int y = preferred.y() - depth * TRUNK_STEP;
                if (ring == 0) {
                    Coord center = new Coord(preferred.x(), y);
                    if (fits(center, parent, requireGap, avoidTrunkColumn)) return center;
                    continue;
                }
                Coord left = new Coord(preferred.x() - ring * SIDE_STEP, y);
                if (fits(left, parent, requireGap, avoidTrunkColumn)) return left;
                Coord right = new Coord(preferred.x() + ring * SIDE_STEP, y);
                if (fits(right, parent, requireGap, avoidTrunkColumn)) return right;
            }
        }
        return null;
    }

    private boolean fits(Coord candidate, Coord parent, boolean requireGap, boolean avoidTrunkColumn) {
        if (occupied.contains(candidate) || candidate.equals(parent) || candidate.equals(rootCoord())) {
            return false;
        }
        if (avoidTrunkColumn && candidate.x() == startX) {
            return false;
        }
        return !requireGap || !touchesPlacedNode(candidate);
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
