package com.trinityforge.skilltree.generator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * スキルツリー系GUIが共有する「格子上のコネクタ配線」ロジック。
 *
 * <p>元は {@link SkillTreeLayout}（経路探索）/ {@code SkillTreeProgressionGenerator}（向きコード）/
 * ネイティブGUIのキャンバス（重なり合成）に分散していたものを、2026-07-29 の
 * アチーブメント・ノードGUI追加に合わせて1か所へ集約した。<b>アチーブメント側で同じ配線規則を
 * 書き直すと、リソースパックのコネクタ21形状との対応が2系統に分かれて必ずずれる</b>ため。
 *
 * <p>格子の向きは全体で共通: y は下方向に増える（N=上=y小 / S=下=y大 / E=右=x大 / W=左=x小）。
 */
public final class GridConnectorRouting {

    private GridConnectorRouting() {
    }

    /**
     * {@code from} から {@code to} までの直交経路（両端のノードセルを含む）を返す。
     *
     * <p>{@code blocked} に含まれるセルは通らない（他ノードの上を線が横切らないようにするため）。
     * ただし両端だけは例外で、必ず通る。探索範囲は関係セルの外接矩形を {@code pad} だけ広げた範囲。
     *
     * @throws IllegalStateException 到達不能なとき（格子が分断されている＝設定が壊れている）
     */
    public static List<Coord> route(Coord from, Coord to, Set<Coord> blocked, int pad) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Set<Coord> obstacles = blocked == null ? Set.of() : blocked;
        if (from.equals(to)) {
            return List.of(from);
        }
        int minX = bound(obstacles, from, to, true, true) - pad;
        int maxX = bound(obstacles, from, to, true, false) + pad;
        int minY = bound(obstacles, from, to, false, true) - pad;
        int maxY = bound(obstacles, from, to, false, false) + pad;

        ArrayDeque<Coord> queue = new ArrayDeque<>();
        Map<Coord, Coord> previous = new HashMap<>();
        Set<Coord> seen = new HashSet<>();
        queue.add(from);
        seen.add(from);
        int[][] directions = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};
        while (!queue.isEmpty()) {
            Coord current = queue.removeFirst();
            if (current.equals(to)) {
                break;
            }
            for (int[] direction : directions) {
                Coord next = new Coord(current.x() + direction[0], current.y() + direction[1]);
                if (next.x() < minX || next.x() > maxX || next.y() < minY || next.y() > maxY) {
                    continue;
                }
                boolean blockedNode = obstacles.contains(next) && !next.equals(from) && !next.equals(to);
                if (blockedNode || !seen.add(next)) {
                    continue;
                }
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
            if (cursor.equals(from)) {
                break;
            }
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static int bound(Set<Coord> obstacles, Coord from, Coord to, boolean xAxis, boolean min) {
        int a = xAxis ? from.x() : from.y();
        int b = xAxis ? to.x() : to.y();
        int result = min ? Math.min(a, b) : Math.max(a, b);
        for (Coord coord : obstacles) {
            int v = xAxis ? coord.x() : coord.y();
            result = min ? Math.min(result, v) : Math.max(result, v);
        }
        return result;
    }

    /**
     * 1つのコネクタセルの2桁向きコードを、前後の経路要素との位置関係から決める。
     * ValhallaMMO 同梱 yml から復号した対応:
     * 縦 = 00(両端node) / 10(上node) / 11(下node) / 06(両端stick)、
     * 横 = 07(両端stick) / 08(東node) / 09(西node)、角 = 12–15。
     *
     * @param beforeNode {@code before} がノードセル（経路の端）なら true
     * @param afterNode  {@code after} がノードセル（経路の端）なら true
     */
    public static String suffix(Coord cell, Coord before, Coord after,
                                boolean beforeNode, boolean afterNode) {
        boolean beforeVertical = before.x() == cell.x();
        boolean afterVertical = after.x() == cell.x();

        if (beforeVertical && afterVertical) {
            boolean nodeAbove = (beforeNode && before.y() < cell.y()) || (afterNode && after.y() < cell.y());
            boolean nodeBelow = (beforeNode && before.y() > cell.y()) || (afterNode && after.y() > cell.y());
            if (nodeAbove && nodeBelow) {
                return "00";
            }
            if (nodeAbove) {
                return "10";
            }
            if (nodeBelow) {
                return "11";
            }
            return "06";
        }
        if (!beforeVertical && !afterVertical) {
            boolean nodeEast = (beforeNode && before.x() > cell.x()) || (afterNode && after.x() > cell.x());
            boolean nodeWest = (beforeNode && before.x() < cell.x()) || (afterNode && after.x() < cell.x());
            if (nodeEast && !nodeWest) {
                return "08";
            }
            if (nodeWest && !nodeEast) {
                return "09";
            }
            return "07";
        }

        int arms = directionMask(cell, before) | directionMask(cell, after);
        return switch (arms) {
            case 0b0011 -> "12"; // north + east
            case 0b0110 -> "13"; // east + south
            case 0b1100 -> "14"; // south + west
            case 0b1001 -> "15"; // west + north
            default -> throw new IllegalStateException("invalid connector corner at " + cell.format());
        };
    }

    /**
     * 同じセルに2本の線が乗ったときの合成。両方の「腕」の和で形状を選び直す
     * （合成できない組み合わせは第1引数をそのまま使う＝従来挙動）。
     *
     * <p>同じ親の扇が横に分岐するセルでは十字・T字になってよい。一方、
     * {@link #merge(String, String, boolean) joinPerpendicular=false} は
     * 「縦の続き」と「別親の横の扇」が同じ通路セルを共有するときに使う。
     * 十字に描くと GUI に境目が無いせいで独立した列が格子に見える。
     */
    public static String merge(String first, String second) {
        return merge(first, second, true);
    }

    /**
     * @param joinPerpendicular {@code false} のとき、純粋な縦と純粋な横は腕を足さず縦を残す
     */
    public static String merge(String first, String second, boolean joinPerpendicular) {
        if (!joinPerpendicular && perpendicularStraights(first, second)) {
            return verticalStraight(first, second);
        }
        int arms = arms(first) | arms(second);
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

    private static boolean perpendicularStraights(String first, String second) {
        return (isVerticalStraight(first) && isHorizontalStraight(second))
                || (isHorizontalStraight(first) && isVerticalStraight(second));
    }

    private static boolean isVerticalStraight(String suffix) {
        int mask = arms(suffix);
        return (mask & 0b0101) != 0 && (mask & 0b1010) == 0;
    }

    private static boolean isHorizontalStraight(String suffix) {
        int mask = arms(suffix);
        return (mask & 0b1010) != 0 && (mask & 0b0101) == 0;
    }

    private static String verticalStraight(String first, String second) {
        return isVerticalStraight(first) ? first : second;
    }

    /** 向きコードが持つ「腕」のビットマスク (N=1 / E=2 / S=4 / W=8)。 */
    public static int arms(String suffix) {
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

    private static int directionMask(Coord from, Coord to) {
        if (to.y() < from.y()) {
            return 0b0001;
        }
        if (to.x() > from.x()) {
            return 0b0010;
        }
        if (to.y() > from.y()) {
            return 0b0100;
        }
        if (to.x() < from.x()) {
            return 0b1000;
        }
        return 0;
    }
}
