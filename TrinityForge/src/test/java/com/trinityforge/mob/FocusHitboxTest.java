package com.trinityforge.mob;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HPテキストディスプレイの視線判定と位置(2026-08-18 ユーザー確定要件)の回帰テスト。
 *
 * <p>固定する受け入れ条件は 4 つ:
 * <ol>
 *   <li>モブのヒットボックスの<b>どこを見ても</b>表示されること</li>
 *   <li>逆にヒットボックスから<b>視線を逸らしたら表示されない</b>こと</li>
 *   <li>ヒットボックスの<b>一番高い位置の中心の少し上</b>にラベルを出すこと</li>
 *   <li>数ブロック級のモブは<b>現状の位置を目安</b>にすること(＝今の見え方を変えない)</li>
 * </ol>
 *
 * <p>旧実装は「目の位置どうしの角度が一定以内」という<b>点と点の円錐</b>だったので、
 * ヒットボックスが大きいほど体の端が判定から外れた(エンダードラゴン/ガストで
 * 「向いているのに出ない」)。ここでは幅の広いモブの<b>端</b>を見るケースを明示的に置いて、
 * 円錐方式へ戻したら落ちるようにしてある。
 */
class FocusHitboxTest {

    /** ゾンビ相当(幅 0.6 / 高さ 1.95)を原点付近に置いたヒットボックス。 */
    private static BoundingBox zombieBox() {
        return new BoundingBox(-0.3, 0.0, -0.3, 0.3, 1.95, 0.3);
    }

    /** ガスト相当(4x4x4)。目より上に体が続く代表例。 */
    private static BoundingBox ghastBox() {
        return new BoundingBox(-2.0, 0.0, -2.0, 2.0, 4.0, 2.0);
    }

    private static double distanceFrom(BoundingBox box, Vector eye, Vector target) {
        Vector look = target.clone().subtract(eye).normalize();
        return FocusHitbox.lookDistance(box, eye, look, 20.0);
    }

    @Test
    @DisplayName("数ブロック級のモブのラベル位置が旧実装(目の高さ+0.55)とほぼ一致する")
    void smallMobLabelHeightMatchesLegacyPlacement() {
        // 旧実装: ゾンビの目の高さ 1.74 + 0.55 = 2.29
        double legacy = 1.74 + 0.55;
        double actual = FocusHitbox.labelY(zombieBox());

        assertEquals(legacy, actual, 0.05,
                "「数ブロック程度のモブは現状正しい位置に表示されているのでそこを目安にすること」"
                        + "= 小型モブのラベル高さは変えてはいけない");
    }

    @Test
    @DisplayName("ラベルはヒットボックスの最上面より上に出る(体に埋まらない)")
    void labelSitsAboveTheHitboxTop() {
        BoundingBox ghast = ghastBox();

        assertTrue(FocusHitbox.labelY(ghast) > ghast.getMaxY(),
                "大型モブでラベルが体に埋まっていたのが元の不具合。最上面より必ず上");
        assertEquals(ghast.getMaxY() + FocusHitbox.TOP_OFFSET, FocusHitbox.labelY(ghast), 1e-9);
    }

    @Test
    @DisplayName("ヒットボックスのどこを見ても当たる(中心・上端・下端・左右端・角)")
    void everyPointOfTheHitboxIsHit() {
        BoundingBox ghast = ghastBox();
        // 近距離にするのは意図的。大型モブは近いほど大きな角度を占めるので、
        // 「点と点の角度」で判定していた旧実装が最も派手に落ちる配置になる
        // (どれだけ落ちるかは legacyConeWouldHaveMissedTheEdge が数値で固定している)。
        Vector eye = new Vector(0.0, 2.0, -4.0);

        Vector[] aims = {
                new Vector(0.0, 2.0, 0.0),    // 中心
                new Vector(0.0, 3.9, 0.0),    // 上端
                new Vector(0.0, 0.1, 0.0),    // 下端
                new Vector(-1.9, 2.0, 0.0),   // 左端
                new Vector(1.9, 2.0, 0.0),    // 右端
                new Vector(-1.9, 3.9, 0.0),   // 角
                new Vector(1.9, 0.1, 0.0),    // 反対の角
        };
        for (Vector aim : aims) {
            assertTrue(FocusHitbox.isHit(distanceFrom(ghast, eye, aim)),
                    "ヒットボックス内の " + aim + " を見ているのに当たらない"
                            + "(旧実装の円錐判定は端を見ると落ちた)");
        }
    }

    /**
     * RED 証明。旧実装の救済判定は「目の位置どうしの角度が {@code LOOK_CONE_DOT = 0.90}(≒25.8°)以内」
     * という<b>点と点の円錐</b>だった。ここで使っている配置がその円錐から外れることを数値で固定して、
     * 円錐方式へ戻したら {@link #everyPointOfTheHitboxIsHit} が確実に落ちるようにしておく。
     */
    @Test
    @DisplayName("旧実装の円錐判定なら取りこぼす配置であることを固定する")
    void legacyConeWouldHaveMissedTheEdge() {
        BoundingBox ghast = ghastBox();
        Vector eye = new Vector(0.0, 2.0, -4.0);
        Vector corner = new Vector(-1.9, 3.9, 0.0);
        Vector center = new Vector(0.0, 2.0, 0.0);

        double legacyDot = corner.clone().subtract(eye).normalize()
                .dot(center.clone().subtract(eye).normalize());

        assertTrue(legacyDot < 0.90,
                "旧 LOOK_CONE_DOT=0.90 では体の角を見た時点で判定から外れる(実測 " + legacyDot + ")");
        assertTrue(FocusHitbox.isHit(distanceFrom(ghast, eye, corner)),
                "新実装は同じ視線でちゃんと当たる");
    }

    @Test
    @DisplayName("ヒットボックスから視線を逸らすと当たらない")
    void lookingAwayFromTheHitboxMisses() {
        BoundingBox ghast = ghastBox();
        Vector eye = new Vector(0.0, 2.0, -10.0);

        // 判定の甘さ(TOLERANCE)を明確に超えて外側を狙う
        assertFalse(FocusHitbox.isHit(distanceFrom(ghast, eye, new Vector(4.0, 2.0, 0.0))),
                "ヒットボックスの右外を見ているのに当たってはいけない");
        assertFalse(FocusHitbox.isHit(distanceFrom(ghast, eye, new Vector(0.0, 7.0, 0.0))),
                "ヒットボックスの上外を見ているのに当たってはいけない");
        assertFalse(FocusHitbox.isHit(distanceFrom(ghast, eye, new Vector(0.0, 2.0, -20.0))),
                "真後ろを向いているのに当たってはいけない");
    }

    @Test
    @DisplayName("小型モブでも輪郭の外を見れば当たらない(判定が甘くなりすぎていない)")
    void smallMobStillMissesWhenLookingAside() {
        BoundingBox zombie = zombieBox();
        Vector eye = new Vector(0.0, 1.6, -5.0);

        assertTrue(FocusHitbox.isHit(distanceFrom(zombie, eye, new Vector(0.0, 1.0, 0.0))),
                "正面のゾンビは当たる");
        assertFalse(FocusHitbox.isHit(distanceFrom(zombie, eye, new Vector(1.5, 1.0, 0.0))),
                "ゾンビの横 1.5 ブロック先を見ているのに当たってはいけない"
                        + "(旧実装の円錐は小型モブでは広すぎた)");
    }

    @Test
    @DisplayName("ヒットボックスに埋もれている(目が内側)なら距離0で当たり")
    void eyeInsideTheHitboxCountsAsHit() {
        BoundingBox ghast = ghastBox();
        Vector eye = new Vector(0.0, 2.0, 0.0);

        double distance = FocusHitbox.lookDistance(ghast, eye, new Vector(0.0, 0.0, 1.0), 20.0);

        assertTrue(FocusHitbox.isHit(distance));
        assertEquals(0.0, distance, 1e-9,
                "巨大モブの当たり判定の中に頭が入っている状態でも表示は消えない");
    }

    @Test
    @DisplayName("遮蔽ブロックの手前までしか見ない(壁越しに表示しない)")
    void hitBeyondTheBlockLimitIsRejected() {
        BoundingBox ghast = ghastBox();
        Vector eye = new Vector(0.0, 2.0, -10.0);
        Vector look = new Vector(0.0, 0.0, 1.0);

        // ガストの手前 8 ブロックに壁がある想定
        assertFalse(FocusHitbox.isHit(FocusHitbox.lookDistance(ghast, eye, look, 5.0)),
                "遮蔽ブロックより奥のモブは表示してはいけない");
        assertTrue(FocusHitbox.isHit(FocusHitbox.lookDistance(ghast, eye, look, 20.0)),
                "遮蔽が無ければ同じ視線で当たる(限界距離だけの違いであることの対照)");
    }

    @Test
    @DisplayName("視線が2体を貫くときは手前が近い距離を返す(近い方が選ばれる根拠)")
    void nearerHitboxReportsShorterDistance() {
        Vector eye = new Vector(0.0, 1.6, -10.0);
        Vector look = new Vector(0.0, 0.0, 1.0);
        BoundingBox near = new BoundingBox(-0.3, 1.0, -5.3, 0.3, 2.95, -4.7);
        BoundingBox far = new BoundingBox(-0.3, 1.0, -0.3, 0.3, 2.95, 0.3);

        double nearDistance = FocusHitbox.lookDistance(near, eye, look, 20.0);
        double farDistance = FocusHitbox.lookDistance(far, eye, look, 20.0);

        assertTrue(FocusHitbox.isHit(nearDistance) && FocusHitbox.isHit(farDistance));
        assertTrue(nearDistance < farDistance,
                "重なって見えるときは手前のモブを選ぶ");
    }
}
