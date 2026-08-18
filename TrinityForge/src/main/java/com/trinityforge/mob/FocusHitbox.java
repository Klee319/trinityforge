package com.trinityforge.mob;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * {@link FocusHpDisplay} のラベル位置と視線判定の<b>幾何だけ</b>を切り出したもの。
 *
 * <p>ここに置く理由は検証可能性。判定本体を {@code FocusHpDisplay} の private に埋めると
 * MockBukkit のエンティティを立てないと 1 行も検証できず、MockBukkit は未実装 API を
 * <b>失敗ではなく「中断(SKIPPED)」に化けさせる</b>ので「緑なのに一度も走っていない」状態を
 * 作りやすい。{@link BoundingBox} と {@link Vector} は純粋な数学クラスなので、
 * サーバを立てずに受け入れ条件(ヒットボックスのどこを見ても出る / 外したら出ない /
 * 最上面の中心の少し上に出す)をそのまま固定できる。
 */
final class FocusHitbox {

    /**
     * ヒットボックス最上面からラベル中心までの高さ(2026-08-18 ユーザー確定
     * 「ヒットボックスの一番高い位置の中心の少し上に出す」)。
     *
     * <p>0.35 なのは<b>数ブロック級のモブの現在位置を変えないため</b>。旧実装は
     * {@code 目の高さ + 0.55} で、ゾンビなら 1.74 + 0.55 = 2.29。ゾンビの高さは 1.95 なので
     * {@code 1.95 + 0.35 = 2.30} とほぼ一致する。「今の見え方が正しいものは動かさず、
     * 大型モブだけ埋まらなくなる」という要件そのままの値。
     */
    static final double TOP_OFFSET = 0.35;

    /**
     * ヒットボックス判定の甘さ(ブロック)。ちょうど輪郭をなぞる視線でも拾えるようにするだけの値で、
     * これを大きくすると「モブから視線を外したのに出る」方向に壊れる。
     */
    static final double TOLERANCE = 0.15;

    private FocusHitbox() {
    }

    /** ラベル中心の Y。ヒットボックスの最上面の少し上。 */
    static double labelY(BoundingBox box) {
        return box.getMaxY() + TOP_OFFSET;
    }

    /**
     * 視線 {@code origin + t*look} がヒットボックスを貫くなら交点までの距離、貫かないなら
     * {@link Double#NaN}。目がヒットボックスの中にある(巨大モブに埋もれている)場合は 0。
     *
     * @param limit 有効距離。ブロックに遮られているならその手前までを渡す
     */
    static double lookDistance(BoundingBox box, Vector origin, Vector look, double limit) {
        BoundingBox padded = box.clone().expand(TOLERANCE);
        if (padded.contains(origin)) {
            return 0.0;
        }
        RayTraceResult hit = padded.rayTrace(origin, look, limit);
        return hit == null ? Double.NaN : hit.getHitPosition().distance(origin);
    }

    /** {@link #lookDistance} が「当たった」を返したか。 */
    static boolean isHit(double distance) {
        return !Double.isNaN(distance);
    }
}
