package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig.Emission;
import com.trinityforge.config.domains.SpecialRewardsConfig.Shape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 形状 8 種と、形状ごとに効くパラメータを固定する (2026-08-25 / W-244、実サーバ要望
 * 「形状のバリエーションを増やしたい。発生パラメータも形状に応じて動的に変わるべきでは？
 * 円形拡散なら速度パラメータとか大事」)。
 *
 * <p><b>着手前の状態</b>: 形状は {@code aura} / {@code circle} の2種で、
 * {@code spawnParticle} の {@code extra}(＝粒子の速さ)は<b>どの形状でも 0 のハードコード</b>だった。
 * つまり「輪が外へ広がる」「爆発のように飛び散る」といった<b>向きのある演出は原理的に書けなかった</b>。
 */
class ParticleShapeGeometryTest {

    private static Emission emission(Shape shape, int count, double radius, double speed) {
        Emission base = Emission.of(shape);
        return new Emission(shape, count, radius, speed, base.height(), base.turns(),
                base.arcDegrees(), 0.0);
    }

    @Test
    @DisplayName("形状は8種すべてが呼び出し列を生む（選べるのに何も出ない形状が無い）")
    void everyShapeProducesEmits() {
        for (Shape shape : Shape.values()) {
            List<ParticleGeometry.Emit> emits = ParticleGeometry.emits(Emission.of(shape), 0.0);
            assertTrue(!emits.isEmpty(), shape + " が何も出さない");
        }
    }

    @Test
    @DisplayName("count 0 は空リスト（例外にしない ── 毎tick経路なので同居処理を道連れにする）")
    void zeroCountIsEmptyNotAnException() {
        assertTrue(ParticleGeometry.emits(emission(Shape.CIRCLE, 0, 1.0, 0.0), 0.0).isEmpty());
        assertTrue(ParticleGeometry.emits(null, 0.0).isEmpty());
    }

    /**
     * ★ この2本が「円形拡散に速度が要る」の中身。Bukkit の {@code spawnParticle} は
     * <b>{@code count == 0} のときだけ</b> offset を「飛ばす向き」として読むので、
     * speed を入れたのに count を 1 のまま撃つと<b>向きが揃わない</b>(＝拡散にならない)。
     */
    @Test
    @DisplayName("円は speed=0 なら静止した輪（点1個ずつ・向き無し）")
    void circleWithoutSpeedIsAStaticRing() {
        List<ParticleGeometry.Emit> emits = ParticleGeometry.emits(emission(Shape.CIRCLE, 4, 2.0, 0.0), 0.0);
        assertEquals(4, emits.size());
        for (ParticleGeometry.Emit emit : emits) {
            assertEquals(1, emit.count(), "静止した輪は点1個ずつで撃つ");
            assertEquals(0.0, emit.extra(), 1e-9);
            double dist = Math.hypot(emit.dx(), emit.dz());
            assertEquals(2.0, dist, 1e-9, "半径どおりの位置に並ぶこと");
        }
    }

    @Test
    @DisplayName("円は speed>0 で外向きへ拡散する（count=0 の方向指定モードに切り替わる）")
    void circleWithSpeedBecomesOutwardSpread() {
        List<ParticleGeometry.Emit> emits = ParticleGeometry.emits(emission(Shape.CIRCLE, 4, 2.0, 0.5), 0.0);
        assertEquals(4, emits.size());
        for (ParticleGeometry.Emit emit : emits) {
            assertEquals(0, emit.count(),
                    "count=0 でないと offset が『向き』として読まれない ── ここが 1 に戻ると"
                            + "speed をいくら上げても向きの揃わないただの点になる");
            assertEquals(0.5, emit.extra(), 1e-9, "speed が Bukkit の extra へ渡ること");
            double dir = Math.hypot(emit.offX(), emit.offZ());
            assertEquals(1.0, dir, 1e-9, "向きは単位ベクトル");
            // 向きは中心から外へ(位置と同じ向き)。
            assertTrue(emit.dx() * emit.offX() + emit.dz() * emit.offZ() > 0.0, "外向きであること");
        }
    }

    @Test
    @DisplayName("aura は1回の呼び出しでばらつきの箱を使う（従来の見た目を保つ）")
    void auraIsASingleCloudCall() {
        List<ParticleGeometry.Emit> emits = ParticleGeometry.emits(emission(Shape.AURA, 8, 0.6, 0.0), 0.0);
        assertEquals(1, emits.size());
        ParticleGeometry.Emit emit = emits.get(0);
        assertEquals(8, emit.count());
        assertEquals(0.6, emit.offX(), 1e-9);
        assertEquals(0.6, emit.offY(), 1e-9);
        assertEquals(0.6, emit.offZ(), 1e-9);
    }

    @Test
    @DisplayName("球は半径どおりの球面に乗り、点が重ならない")
    void sphereSitsOnTheSphereSurface() {
        List<ParticleGeometry.Emit> emits = ParticleGeometry.emits(emission(Shape.SPHERE, 12, 1.5, 0.0), 0.0);
        assertEquals(12, emits.size());
        for (ParticleGeometry.Emit emit : emits) {
            double dist = Math.sqrt(emit.dx() * emit.dx() + emit.dy() * emit.dy() + emit.dz() * emit.dz());
            assertEquals(1.5, dist, 1e-9);
        }
        assertNotEquals(emits.get(0).dx(), emits.get(1).dx(), "同じ点に固まっていない");
    }

    @Test
    @DisplayName("射出は speed が本体 ── speed 0 のときだけ『見えない』を避けて雲へ落とす")
    void burstFallsBackToACloudWithoutSpeed() {
        List<ParticleGeometry.Emit> spraying = ParticleGeometry.emits(emission(Shape.BURST, 6, 0.0, 0.4), 0.0);
        assertEquals(6, spraying.size());
        for (ParticleGeometry.Emit emit : spraying) {
            assertEquals(0, emit.count(), "射出は方向指定モード");
            assertEquals(0.0, emit.dx(), 1e-9, "半径ゼロ ── 中心から出る");
            assertEquals(0.4, emit.extra(), 1e-9);
        }
        List<ParticleGeometry.Emit> stalled = ParticleGeometry.emits(emission(Shape.BURST, 6, 0.0, 0.0), 0.0);
        assertEquals(1, stalled.size(), "speed 0 の設定ミスで完全に見えなくなってはいけない");
        assertEquals(6, stalled.get(0).count());
    }

    @Test
    @DisplayName("螺旋は height ぶん昇り、turns ぶん巻く")
    void helixClimbsAndWinds() {
        Emission spiral = new Emission(Shape.HELIX, 9, 1.0, 0.0, 3.0, 2, 120.0, 0.0);
        List<ParticleGeometry.Emit> emits = ParticleGeometry.emits(spiral, 0.0);
        assertEquals(9, emits.size());
        assertEquals(0.0, emits.get(0).dy(), 1e-9, "下端は基準の高さ");
        assertEquals(3.0, emits.get(8).dy(), 1e-9, "上端は height ぶん上");
        for (ParticleGeometry.Emit emit : emits) {
            assertEquals(1.0, Math.hypot(emit.dx(), emit.dz()), 1e-9, "太さは radius で一定");
        }
        // 2巻き9点なら、4点目(t=0.375)は 1.5 周を過ぎて反対側へ回り込んでいる。
        assertTrue(emits.get(3).dx() < 0.0, "巻き数が効いていない(1周しかしていない)");
    }

    @Test
    @DisplayName("柱は縦に伸び、横のばらつきだけ箱に任せる")
    void pillarStacksVertically() {
        Emission pillar = new Emission(Shape.PILLAR, 5, 0.4, 0.0, 2.0, 1, 120.0, 0.0);
        List<ParticleGeometry.Emit> emits = ParticleGeometry.emits(pillar, 0.0);
        assertEquals(5, emits.size());
        assertEquals(0.0, emits.get(0).dy(), 1e-9);
        assertEquals(2.0, emits.get(4).dy(), 1e-9);
        for (ParticleGeometry.Emit emit : emits) {
            assertEquals(0.4, emit.offX(), 1e-9);
            assertEquals(0.0, emit.offY(), 1e-9, "縦は段で決まっているのでばらつかせない");
        }
    }

    /**
     * 弧だけは「プレイヤーがどこを向いているか」に依存する。Bukkit の yaw は南(+Z)が 0 なので、
     * yaw=0 の正面は +Z。ここが逆向きになると<b>敵を殴ったのに背中側へ弧が出る</b>。
     */
    @Test
    @DisplayName("弧は向いている方向の前に出る（yaw の向きを取り違えていない）")
    void arcFacesForward() {
        Emission arc = new Emission(Shape.ARC, 3, 1.0, 0.0, 0.0, 1, 90.0, 0.0);
        List<ParticleGeometry.Emit> south = ParticleGeometry.emits(arc, 0.0);
        assertEquals(3, south.size());
        assertEquals(1.0, south.get(1).dz(), 1e-9, "yaw=0(南向き)の弧の中央は +Z");
        assertEquals(0.0, south.get(1).dx(), 1e-9);

        List<ParticleGeometry.Emit> west = ParticleGeometry.emits(arc, 90.0);
        assertEquals(-1.0, west.get(1).dx(), 1e-9, "yaw=90(西向き)の弧の中央は -X");
    }

    @Test
    @DisplayName("弧の height は端を持ち上げる（中央は上がらない）")
    void arcHeightLiftsTheEdges() {
        Emission arc = new Emission(Shape.ARC, 3, 1.0, 0.0, 0.5, 1, 90.0, 0.0);
        List<ParticleGeometry.Emit> emits = ParticleGeometry.emits(arc, 0.0);
        assertEquals(0.5, emits.get(0).dy(), 1e-9);
        assertEquals(0.0, emits.get(1).dy(), 1e-9);
        assertEquals(0.5, emits.get(2).dy(), 1e-9);
    }

    @Test
    @DisplayName("y-offset は全形状に効く（負値で下げられる）")
    void yOffsetShiftsEveryShape() {
        for (Shape shape : Shape.values()) {
            Emission base = Emission.of(shape);
            Emission lowered = new Emission(shape, base.count(), base.radius(), base.speed(),
                    base.height(), base.turns(), base.arcDegrees(), -1.0);
            // 形状ごとに点の散り方が違う(球は上端から並ぶ)ので、代表点ではなく平均の高さで見る。
            assertEquals(meanHeight(base) - 1.0, meanHeight(lowered), 1e-9,
                    shape + " が y-offset を無視している");
        }
    }

    private static double meanHeight(Emission emission) {
        List<ParticleGeometry.Emit> emits = ParticleGeometry.emits(emission, 0.0);
        double sum = 0.0;
        for (ParticleGeometry.Emit emit : emits) {
            sum += emit.dy();
        }
        return sum / emits.size();
    }

    @Test
    @DisplayName("count と radius は上限で矯正される（クライアントを守る）")
    void outOfRangeValuesAreClamped() {
        Emission absurd = new Emission(Shape.AURA, 100_000, 999.0, 999.0, 999.0, 999, 999.0, 999.0);
        assertEquals(Emission.MAX_COUNT, absurd.count());
        assertEquals(16.0, absurd.radius(), 1e-9);
        assertEquals(8.0, absurd.speed(), 1e-9);
        assertEquals(16, absurd.turns());
        assertEquals(360.0, absurd.arcDegrees(), 1e-9);
        assertEquals(8.0, absurd.yOffset(), 1e-9);

        Emission nan = new Emission(Shape.AURA, 1, Double.NaN, Double.NaN, Double.NaN, 1, Double.NaN, Double.NaN);
        assertEquals(0.0, nan.radius(), 1e-9, "NaN は下限へ倒す(そのまま渡すと spawnParticle が投げる)");
        assertEquals(1.0, nan.arcDegrees(), 1e-9);
    }

    @Test
    @DisplayName("形状ごとの既定値が入っている（形状を変えただけで破綻しない）")
    void everyShapeHasUsableDefaults() {
        assertTrue(Emission.of(Shape.HELIX).height() > 0.0, "螺旋の既定に高さが無いと平らな輪になる");
        assertTrue(Emission.of(Shape.HELIX).turns() >= 1);
        assertTrue(Emission.of(Shape.PILLAR).height() > 0.0, "柱の既定に高さが無いと1段だけになる");
        assertTrue(Emission.of(Shape.BURST).speed() > 0.0, "射出の既定に速さが無いと一点に固まる");
        assertTrue(Emission.of(Shape.ARC).arcDegrees() > 1.0, "弧の既定の開き角が無いと1本の線になる");
        for (Shape shape : Shape.values()) {
            assertTrue(Emission.of(shape).count() > 0, shape + " の既定 count が 0");
        }
    }
}
