package com.trinityforge.combat;

import com.trinityforge.progression.ParticleGeometry;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * 敵の技の当たり判定・予告描画が共有する幾何の純関数（2026-09-04）。
 *
 * <p><b>なぜ箱から円へ直すのか</b>: 現行の {@code MobAbilityExecutor#playersNear} は
 * {@code world.getNearbyEntities(center, r, r, r)} という<b>軸並行の箱</b>で当たり判定をしている。
 * 箱は対角方向で半径の約 1.41 倍先まで判定を伸ばすので、「半径4mの技」のつもりが対角では
 * 約5.7mまで当たる。ここに輪の予告演出だけを重ねても、<b>見えている輪と実際の判定が食い違う</b>
 * （輪の外に立っているのに当たる／輪の中に立っているのに外れる、が両方起きる）。
 * 判定と描画の両方をこのクラスの座標から得ることで、「見た目どおりに避けられる」を保証する。
 *
 * <p>Bukkit 依存は座標型（{@link Location} / {@link Vector}）だけに留め、幾何の本体は
 * double の純関数にしてある。MockBukkit は粒子や当たり判定の実処理を記録しないため、
 * このクラスの単体テストは純関数側だけに当てる（{@code AbilityShapesTest}）。
 */
public final class AbilityShapes {

    /**
     * 垂直方向の判定半径の既定値。「同じ床にいる」を表す値で、段差1〜2ブロックは拾い、
     * 上の階（別のフロア）は拾わない。{@code radius} へフォールバックしない
     * （フォールバックすると「半径6の技は上下6ブロックにも当たる」という現行の理不尽が残る）。
     */
    public static final double DEFAULT_VERTICAL_RADIUS = 3.0;

    /** 輪の点の間隔（ブロック）。1m あたり2点。 */
    public static final double RING_SPACING = 0.5;

    /** 輪の点数の上限。統合版は描画距離が短く、盛ると自分の足元が見えなくなる。 */
    public static final int MAX_RING_POINTS = 64;

    /** 輪の点数の下限。小さい半径でも輪だと分かる最低限。 */
    public static final int MIN_RING_POINTS = 8;

    /** 直線の予告点を刻む間隔（ブロック）。 */
    public static final double LINE_STEP = 1.0;

    private AbilityShapes() {
    }

    /**
     * 中心 {@code (cx, cy, cz)} から見て、対象 {@code (tx, ty, tz)} が半径 {@code radius} の円柱
     * （水平は円、垂直は {@code verticalRadius} の帯）に入っているか。
     *
     * <p>水平と垂直を分けて判定する。{@code radius <= 0} は常に外れる。
     */
    public static boolean hitsCircle(double cx, double cy, double cz, double radius,
                                      double verticalRadius, double tx, double ty, double tz) {
        if (!(radius > 0.0)) {
            return false;
        }
        double dx = tx - cx;
        double dz = tz - cz;
        double dy = ty - cy;
        // わずかな許容(1e-9)を置く理由: 輪の点は sin/cos から計算されるため、数学的には
        // ちょうど半径上でも浮動小数の丸め誤差で半径をごくわずかに超えることがある。
        // 許容が無いと「輪の点そのものが円から外れる」という矛盾した判定になる。
        return dx * dx + dz * dz <= radius * radius + 1.0e-9 && Math.abs(dy) <= verticalRadius;
    }

    /**
     * {@link #hitsCircle(double, double, double, double, double, double, double, double)} の
     * Bukkit 座標版。ワールドが両方 non-null で不一致なら常に false。
     */
    public static boolean hitsCircle(Location center, double radius, double verticalRadius,
                                      Location target) {
        if (center == null || target == null) {
            return false;
        }
        if (center.getWorld() != null && target.getWorld() != null
                && !center.getWorld().equals(target.getWorld())) {
            return false;
        }
        return hitsCircle(center.getX(), center.getY(), center.getZ(), radius, verticalRadius,
                target.getX(), target.getY(), target.getZ());
    }

    /**
     * 始点 {@code from} から向き {@code dir} へ伸ばした長さ {@code length} の線分に、
     * 対象 {@code target} が太さ {@code thickness}（半径として扱う）以内で乗っているか。
     *
     * <p>射影 {@code t = (target - from)・dir} を {@code [0, length]} の範囲に限定してから、
     * 3次元の垂直距離で判定する。cursor ごとに箱を作る現行の {@code BEAM} 実装と違い、
     * 斜めの線でも太さが波打たない。
     *
     * <p>{@code length <= 0} や {@code dir} がゼロベクトルなら常に false。
     */
    public static boolean hitsLine(Vector from, Vector dir, double length, double thickness,
                                    Vector target) {
        if (from == null || dir == null || target == null || !(length > 0.0)) {
            return false;
        }
        double dirLength = dir.length();
        if (!(dirLength > 0.0) || !Double.isFinite(dirLength)) {
            return false;
        }
        Vector unit = dir.clone().multiply(1.0 / dirLength);
        Vector offset = target.clone().subtract(from);
        double t = offset.dot(unit);
        if (t < 0.0 || t > length) {
            return false;
        }
        Vector closest = from.clone().add(unit.clone().multiply(t));
        return closest.distance(target) <= thickness;
    }

    /**
     * 半径 {@code radius} の輪郭を描く点数を、{@link #RING_SPACING} 間隔・
     * {@link #MIN_RING_POINTS}〜{@link #MAX_RING_POINTS} の範囲で求める。
     */
    public static int ringPointCount(double radius) {
        if (!(radius > 0.0)) {
            return 0;
        }
        long raw = Math.round(Math.ceil((2.0 * Math.PI * radius) / RING_SPACING));
        return (int) Math.max(MIN_RING_POINTS, Math.min(MAX_RING_POINTS, raw));
    }

    /**
     * 半径 {@code radius} の円周上のオフセット（{@code {x, z}}）。
     * 円の座標そのものは {@link ParticleGeometry#circleOffsets(int, double)} に既にあるので、
     * <b>自前で円を書かず、点数だけこちらで丸めて委譲する</b>。{@code radius <= 0} は空リスト。
     */
    public static List<double[]> ringOffsets(double radius) {
        int count = ringPointCount(radius);
        if (count <= 0) {
            return List.of();
        }
        return ParticleGeometry.circleOffsets(count, radius);
    }

    /**
     * 始点からの {@code step, 2*step, ..., <= length} のオフセット列（{@code dir} 方向）。
     * {@code length <= 0} / {@code dir} がゼロベクトル / {@code step <= 0} は空リスト。
     */
    public static List<Vector> lineOffsets(Vector dir, double length, double step) {
        List<Vector> out = new ArrayList<>();
        if (dir == null || !(length > 0.0) || !(step > 0.0)) {
            return out;
        }
        double dirLength = dir.length();
        if (!(dirLength > 0.0) || !Double.isFinite(dirLength)) {
            return out;
        }
        Vector unit = dir.clone().multiply(1.0 / dirLength);
        // 浮動小数の丸め誤差で最後の1点を取りこぼさないよう、わずかな許容を足す。
        double limit = length + 1.0e-9;
        for (double d = step; d <= limit; d += step) {
            double clamped = Math.min(d, length);
            out.add(unit.clone().multiply(clamped));
        }
        return out;
    }
}
