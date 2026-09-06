package com.trinityforge.combat;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AbilityShapes} の幾何純関数を固定する（2026-09-04、機構1「範囲判定の円化」）。
 *
 * <p>Bukkit 非依存で書ける部分だけをここで固定する（{@code Vector} は座標を運ぶだけの
 * 純粋な値クラスで、サーバやMockBukkitを要求しない）。実際に円が描かれたか・粒子が
 * 出たかは JUnit では検証できない（design doc「検証アダプタ」節）。
 */
class AbilityShapesTest {

    @Test
    @DisplayName("ringOffsets の全点は hitsCircle の境界上にあり、半径ちょうどで命中する")
    void ringPointsAreOnTheBoundary() {
        double radius = 4.0;
        List<double[]> offsets = AbilityShapes.ringOffsets(radius);

        assertTrue(offsets.size() >= AbilityShapes.MIN_RING_POINTS);
        for (double[] offset : offsets) {
            double dist = Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1]);
            assertEquals(radius, dist, 1.0e-9, "輪の点は半径ちょうどに乗るべき");
            assertTrue(AbilityShapes.hitsCircle(0, 64, 0, radius, 3.0, offset[0], 64, offset[1]),
                    "輪の点そのものは円の内側（境界含む）として命中するべき");
        }
    }

    @Test
    @DisplayName("輪のすぐ外側（半径+0.01）は外れる")
    void justOutsideRingMisses() {
        double radius = 4.0;
        List<double[]> offsets = AbilityShapes.ringOffsets(radius);
        double[] first = offsets.get(0);
        double scale = (radius + 0.01) / radius;

        assertFalse(AbilityShapes.hitsCircle(0, 64, 0, radius, 3.0,
                first[0] * scale, 64, first[1] * scale));
    }

    @Test
    @DisplayName("半径4の円で45度方向5.0ブロックの点は外れる（箱時代の回帰）")
    void diagonalPointOutsideCircleMisses() {
        double radius = 4.0;
        double distance = 5.0;
        double component = distance / Math.sqrt(2.0); // 45度成分

        // 旧実装(軸並行の箱 r,r,r)なら command|dx|<=4 かつ |dz|<=4 で命中していたはずの点。
        assertTrue(Math.abs(component) <= radius, "旧の箱判定なら命中していたはずの前提を確認");
        assertFalse(AbilityShapes.hitsCircle(0, 64, 0, radius, 3.0, component, 64, component),
                "円化後は対角5.0ブロックの点が外れるべき");
    }

    @Test
    @DisplayName("垂直方向は verticalRadius を超えると外れる")
    void verticalRadiusIsRespected() {
        assertTrue(AbilityShapes.hitsCircle(0, 64, 0, 4.0, 3.0, 1.0, 66.5, 1.0));
        assertFalse(AbilityShapes.hitsCircle(0, 64, 0, 4.0, 3.0, 1.0, 68.0, 1.0),
                "|dy|=4 > verticalRadius=3 は外れるべき");
    }

    @Test
    @DisplayName("radius<=0 は常に外れる")
    void nonPositiveRadiusAlwaysMisses() {
        assertFalse(AbilityShapes.hitsCircle(0, 64, 0, 0.0, 3.0, 0, 64, 0));
        assertFalse(AbilityShapes.hitsCircle(0, 64, 0, -1.0, 3.0, 0, 64, 0));
        assertEquals(List.of(), AbilityShapes.ringOffsets(0.0));
    }

    @Test
    @DisplayName("斜め45度の線と軸方向の線は太さ判定が同じ（cursorごとの箱をやめた効果）")
    void diagonalAndAxisLineHaveSameThickness() {
        double length = 10.0;
        double thickness = 1.0;

        // 軸方向 (+X) の線。太さの境界上(dz=thickness)の点はぎりぎり命中。
        Vector axisFrom = new Vector(0, 64, 0);
        Vector axisDir = new Vector(1, 0, 0);
        assertTrue(AbilityShapes.hitsLine(axisFrom, axisDir, length, thickness,
                new Vector(5.0, 64, thickness - 1.0e-9)));
        assertFalse(AbilityShapes.hitsLine(axisFrom, axisDir, length, thickness,
                new Vector(5.0, 64, thickness + 0.5)));

        // 斜め45度の線。垂直距離が同じ thickness なら同じ結果になるべき。
        Vector diagDir = new Vector(1, 0, 1);
        Vector onLine = diagDir.clone().normalize().multiply(5.0).add(axisFrom);
        // 線に垂直な単位ベクトル（水平面内、45度線に直交）は (1,-1)/sqrt(2) 方向。
        Vector perpUnit = new Vector(1, 0, -1).normalize();
        Vector nearPoint = onLine.clone().add(perpUnit.clone().multiply(thickness - 1.0e-9));
        Vector farPoint = onLine.clone().add(perpUnit.clone().multiply(thickness + 0.5));

        assertTrue(AbilityShapes.hitsLine(axisFrom, diagDir, length, thickness, nearPoint));
        assertFalse(AbilityShapes.hitsLine(axisFrom, diagDir, length, thickness, farPoint));
    }

    @Test
    @DisplayName("hitsLine は length<=0 / ゼロベクトルで常に false")
    void hitsLineDegenerateCasesAreFalse() {
        Vector from = new Vector(0, 64, 0);
        assertFalse(AbilityShapes.hitsLine(from, new Vector(1, 0, 0), 0.0, 1.0,
                new Vector(0, 64, 0)));
        assertFalse(AbilityShapes.hitsLine(from, new Vector(0, 0, 0), 10.0, 1.0,
                new Vector(1, 64, 0)));
    }

    @Test
    @DisplayName("hitsLine は射影が[0,length]の外なら外れる")
    void hitsLineOutOfRangeMisses() {
        Vector from = new Vector(0, 64, 0);
        Vector dir = new Vector(1, 0, 0);
        // t = -1 (始点より手前)
        assertFalse(AbilityShapes.hitsLine(from, dir, 10.0, 1.0, new Vector(-1, 64, 0)));
        // t = 11 (終点より先)
        assertFalse(AbilityShapes.hitsLine(from, dir, 10.0, 1.0, new Vector(11, 64, 0)));
    }

    @Test
    @DisplayName("ringPointCount は0.5間隔で計算され、64で頭打ち・8を下回らない")
    void ringPointCountIsClamped() {
        // 2*PI*20/0.5 = 251.3 -> 64 に丸められる
        assertEquals(AbilityShapes.MAX_RING_POINTS, AbilityShapes.ringPointCount(20.0));
        // 小半径でも最低8点。
        assertEquals(AbilityShapes.MIN_RING_POINTS, AbilityShapes.ringPointCount(0.1));
        assertEquals(0, AbilityShapes.ringPointCount(0.0));
        assertEquals(0, AbilityShapes.ringPointCount(-5.0));

        // 半径4: ceil(2*PI*4/0.5) = ceil(50.27) = 51 (8〜64の範囲内なのでそのまま)
        assertEquals(51, AbilityShapes.ringPointCount(4.0));
    }

    @Test
    @DisplayName("lineOffsets は step刻みでlengthを超えない")
    void lineOffsetsRespectStepAndLength() {
        Vector dir = new Vector(1, 0, 0);
        List<Vector> offsets = AbilityShapes.lineOffsets(dir, 3.2, 1.0);

        // 1.0, 2.0, 3.0 の3点（3.2を超える4.0は含まれない）
        assertEquals(3, offsets.size());
        assertEquals(1.0, offsets.get(0).getX(), 1.0e-9);
        assertEquals(2.0, offsets.get(1).getX(), 1.0e-9);
        assertEquals(3.0, offsets.get(2).getX(), 1.0e-9);
        for (Vector v : offsets) {
            assertTrue(v.getX() <= 3.2 + 1.0e-9);
        }
    }

    @Test
    @DisplayName("lineOffsets の退化ケース（length<=0 / dir ゼロ / step<=0）は空")
    void lineOffsetsDegenerateCasesAreEmpty() {
        Vector dir = new Vector(1, 0, 0);
        assertEquals(List.of(), AbilityShapes.lineOffsets(dir, 0.0, 1.0));
        assertEquals(List.of(), AbilityShapes.lineOffsets(new Vector(0, 0, 0), 10.0, 1.0));
        assertEquals(List.of(), AbilityShapes.lineOffsets(dir, 10.0, 0.0));
        assertEquals(List.of(), AbilityShapes.lineOffsets(null, 10.0, 1.0));
    }
}
