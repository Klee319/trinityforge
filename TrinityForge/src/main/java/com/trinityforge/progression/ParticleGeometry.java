package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig.Emission;
import com.trinityforge.config.domains.SpecialRewardsConfig.Shape;

import java.util.ArrayList;
import java.util.List;

/**
 * 形状 → 「Bukkit の {@code spawnParticle} を何回どう呼ぶか」の純関数 (Bukkit 非依存・単体テスト可)。
 * Bukkit 側の駆動は {@link ParticleEffectService}。
 *
 * <h2>なぜ「点の座標」ではなく呼び出しの記述({@link Emit})を返すのか</h2>
 * Bukkit の {@code spawnParticle(particle, loc, count, offsetX, offsetY, offsetZ, extra)} は
 * <b>{@code count} が 0 のときだけ意味が入れ替わる</b>: {@code count > 0} なら
 * {@code offset*} は「ばらつきの箱」・{@code extra} は速さだが、<b>{@code count == 0} なら
 * {@code offset*} が「飛ばす方向ベクトル」・{@code extra} が「その速さ」になる</b>。
 * 「円形に拡散させる」「爆発のように全方向へ射出する」は後者でしか表現できない
 * (前者は箱の中にランダムに置くだけなので、どれだけ speed を上げても<b>向きが揃わない</b>)。
 * つまり形状によって呼び出しの<em>形そのもの</em>が変わるので、座標だけ返す作りにはできない。
 *
 * <p>すべて決定的(乱数を使わない)。ばらつきが要る形状は Bukkit 側の箱ばらつきに委ねる。
 */
public final class ParticleGeometry {

    private ParticleGeometry() {
    }

    /**
     * {@code spawnParticle} 1回ぶん。
     *
     * @param dx     基準点からのオフセット(ブロック)
     * @param dy     同上
     * @param dz     同上
     * @param count  {@code spawnParticle} の {@code count}。<b>0 = 方向指定モード</b>
     * @param offX   {@code count>0} ならばらつきの箱、{@code count==0} なら方向ベクトル
     * @param offY   同上
     * @param offZ   同上
     * @param extra  粒子の速さ
     */
    public record Emit(double dx, double dy, double dz,
                       int count, double offX, double offY, double offZ, double extra) {
    }

    /**
     * この発生パラメータを描くための呼び出し列。{@code yawDegrees} は {@link Shape#ARC} だけが使う
     * (プレイヤーが向いている方向へ弧を向けるため)。
     *
     * <p>{@code count <= 0} は空リスト ── 例外ではなく「何も出ない」に倒す。
     * パーティクルは毎tick回る経路なので、設定ミス1件で例外ログが埋まると同居処理まで道連れになる。
     */
    public static List<Emit> emits(Emission emission, double yawDegrees) {
        return emits(emission, yawDegrees, 0.0);
    }

    /**
     * {@code phaseTurns} は螺旋の「今どこまで回したか」（周）。止まっているプレイヤーでも
     * 螺旋に見えるように、駆動側が時刻で進める。他の形状は無視する。
     */
    public static List<Emit> emits(Emission emission, double yawDegrees, double phaseTurns) {
        List<Emit> out = new ArrayList<>();
        if (emission == null || emission.count() <= 0) {
            return out;
        }
        int count = emission.count();
        double r = emission.radius();
        double speed = emission.speed();
        double y = emission.yOffset();
        double phase = Double.isFinite(phaseTurns) ? phaseTurns : 0.0;
        switch (emission.shape()) {
            case POINT -> out.add(new Emit(0, y, 0, count, 0, 0, 0, speed));
            case AURA -> out.add(new Emit(0, y, 0, count, r, r, r, speed));
            case CIRCLE -> {
                for (int i = 0; i < count; i++) {
                    double angle = (2 * Math.PI * i) / count;
                    double cos = Math.cos(angle);
                    double sin = Math.sin(angle);
                    out.add(directional(r * cos, y, r * sin, cos, 0, sin, speed));
                }
            }
            case SPHERE -> {
                for (double[] unit : fibonacciSphere(count)) {
                    out.add(directional(r * unit[0], y + r * unit[1], r * unit[2],
                            unit[0], unit[1], unit[2], speed));
                }
            }
            case BURST -> {
                // radius は使わない(半径ゼロの爆発)。speed が 0 だと一点に固まって見えるので、
                // そのときだけ「点の雲」へ落とす —— 設定ミスで完全に見えなくなるのを避ける。
                if (speed <= 0.0) {
                    out.add(new Emit(0, y, 0, count, 0.1, 0.1, 0.1, 0.0));
                } else {
                    for (double[] unit : fibonacciSphere(count)) {
                        out.add(new Emit(0, y, 0, 0, unit[0], unit[1], unit[2], speed));
                    }
                }
            }
            case HELIX -> {
                double turns = emission.turns();
                for (int i = 0; i < count; i++) {
                    double t = count == 1 ? 0.0 : (double) i / (count - 1);
                    // 位相を足さないと、点の少ない螺旋は左右2本のギザギザにしか見えない
                    // （出荷の花びら螺旋はかつて 3点×3巻きで、静止画がまさにそれだった）。
                    double angle = 2 * Math.PI * (turns * t + phase);
                    out.add(new Emit(r * Math.cos(angle), y + emission.height() * t,
                            r * Math.sin(angle), 1, 0, 0, 0, speed));
                }
            }
            case PILLAR -> {
                for (int i = 0; i < count; i++) {
                    double t = count == 1 ? 0.0 : (double) i / (count - 1);
                    // 横のばらつきだけ箱に任せる(縦は段で決まっているので 0)。
                    out.add(new Emit(0, y + emission.height() * t, 0, 1, r, 0, r, speed));
                }
            }
            case TRAIL -> {
                // 後ろへ等間隔に並べる。前方は (-sin(yaw), cos(yaw)) なので、後ろはその符号反転。
                // ⚠ ここに渡ってくる yawDegrees は「向いている方向」ではなく
                //   【進んでいる方向】(止まっているときだけ向いている方向)。決めるのは駆動側
                //   (ParticleEffectService)で、この層は「渡された向きの後ろ」だけを描く。
                double trailYaw = Math.toRadians(yawDegrees);
                double backX = Math.sin(trailYaw);
                double backZ = -Math.cos(trailYaw);
                for (int i = 0; i < count; i++) {
                    // 足元そのもの(t=0)は置かない。プレイヤーの真下は本人の視点で見えないので、
                    // 1点ぶん無駄になる。
                    double t = (i + 1.0) / count;
                    out.add(directional(r * backX * t, y, r * backZ * t, backX, 0, backZ, speed));
                }
            }
            case ARC -> {
                double sweep = Math.toRadians(emission.arcDegrees());
                // Bukkit の yaw は「南(+Z)が 0 で時計回り」。前方は (-sin(yaw), cos(yaw))。
                double yaw = Math.toRadians(yawDegrees);
                for (int i = 0; i < count; i++) {
                    double t = count == 1 ? 0.5 : (double) i / (count - 1);
                    double angle = yaw - sweep / 2 + sweep * t;
                    double dx = -Math.sin(angle);
                    double dz = Math.cos(angle);
                    // height は弧の傾き(端を持ち上げる)。中央 0 / 両端 +height。
                    double lift = emission.height() * Math.abs(t - 0.5) * 2.0;
                    out.add(directional(r * dx, y + lift, r * dz, dx, 0, dz, speed));
                }
            }
        }
        return out;
    }

    /**
     * {@code speed > 0} なら方向指定モード({@code count = 0})、そうでなければ点1個。
     * この分岐を1箇所に閉じ込めておかないと、形状を足すたびに「speed を書いたのに動かない」形状が生える。
     */
    private static Emit directional(double dx, double dy, double dz,
                                    double dirX, double dirY, double dirZ, double speed) {
        if (speed > 0.0) {
            return new Emit(dx, dy, dz, 0, dirX, dirY, dirZ, speed);
        }
        return new Emit(dx, dy, dz, 1, 0, 0, 0, 0.0);
    }

    /**
     * 球面上に {@code count} 個をほぼ均等に置く単位ベクトル列(黄金角のらせん)。
     * 乱数を使わずに偏りなく散らせるので、テストで座標を固定できる。
     */
    private static List<double[]> fibonacciSphere(int count) {
        List<double[]> out = new ArrayList<>(count);
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < count; i++) {
            double unitY = count == 1 ? 0.0 : 1.0 - (2.0 * i) / (count - 1);
            double ring = Math.sqrt(Math.max(0.0, 1.0 - unitY * unitY));
            double theta = golden * i;
            out.add(new double[] {Math.cos(theta) * ring, unitY, Math.sin(theta) * ring});
        }
        return out;
    }

    /**
     * {@code count} 個の {@code [dx, dz]}(半径 {@code radius} の水平円)。
     *
     * <p>{@link Shape#CIRCLE} を {@link #emits} に統合したあとも残してある ── 円周上の座標そのものを
     * 他所(コネクタ配置など)が使えるようにしておくため。{@code count <= 0} / {@code radius <= 0} は空。
     */
    public static List<double[]> circleOffsets(int count, double radius) {
        List<double[]> points = new ArrayList<>();
        if (count <= 0 || radius <= 0) {
            return points;
        }
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI * i) / count;
            points.add(new double[] {radius * Math.cos(angle), radius * Math.sin(angle)});
        }
        return points;
    }
}
