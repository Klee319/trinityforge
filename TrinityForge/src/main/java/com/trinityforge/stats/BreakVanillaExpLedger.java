package com.trinityforge.stats;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 破壊時バニラEXP({@code feature:break-vanilla-exp-<skill>})の付与量を決める台帳
 * (2026-08-18 ユーザー要望「破壊時EXP系のもらえるバニラ経験値が多すぎる。現状の 1/4 程度の量にして
 * 設定もできるようにして」)。
 *
 * <h2>なぜ端数の持ち越しが要るのか</h2>
 * {@code Player#giveExp} は整数しか受け取れない。旧実装はベース 1 EXP を
 * {@code Math.round(1 * (1 + bonus))} して渡していたので、<b>基準値を 0.25 に下げただけでは
 * 四捨五入で 0 か 1 に化けて「1/4」にならない</b>(bonus 0 なら常に 0＝機能が死ぬ、
 * bonus 1.5 なら 0.625→1 で 1/3 しか減らない)。
 * そこでプレイヤーごとに<b>端数を持ち越し</b>、1 貯まったぶんだけ整数で渡す
 * ── 「4回壊すと 1 EXP」のように<b>期待値どおり・決定的</b>に減る
 * (乱数で撒くと同じ操作でブレて検証できない)。
 *
 * <h2>端数はメモリだけ</h2>
 * 再起動/再ログインで端数は失われる。最大でも 1 EXP 未満の取りこぼしなので永続化しない
 * (永続化コストのほうが害)。
 */
public final class BreakVanillaExpLedger {

    /**
     * ベース付与量の既定値。**{@code stats/skill-exp.yml} の
     * {@code break-vanilla-exp.base-exp} と必ず一致させること**(yml を消した環境だけ挙動が変わる
     * drift を作らないため)。旧実装の 1.0 に対する 1/4。
     */
    public static final double DEFAULT_BASE_EXP = 0.25;

    private final Map<UUID, Double> carry = new ConcurrentHashMap<>();

    /**
     * 今回の破壊で実際に渡す整数EXP。端数はプレイヤーごとに持ち越す。
     *
     * @param playerId プレイヤー
     * @param baseExp  1回の破壊あたりのベース量({@code break-vanilla-exp.base-exp})。
     *                 0以下・非有限なら 0 を返し、持ち越しにも触らない
     * @param bonus    合算済みの倍率ボーナス(負値は0として扱う。従来と同じ {@code 1 + bonus} 倍)
     */
    public int take(UUID playerId, double baseExp, double bonus) {
        if (playerId == null || !Double.isFinite(baseExp) || baseExp <= 0.0) {
            return 0;
        }
        double gain = baseExp * (1.0 + Math.max(0.0, Double.isFinite(bonus) ? bonus : 0.0));
        if (!Double.isFinite(gain) || gain <= 0.0) {
            return 0;
        }
        double total = carry.getOrDefault(playerId, 0.0) + gain;
        int whole = (int) Math.floor(total);
        double remainder = total - whole;
        if (remainder <= 0.0) {
            carry.remove(playerId);
        } else {
            carry.put(playerId, remainder);
        }
        return Math.max(0, whole);
    }

    /** 退出時に端数を捨てる(オンライン人数ぶんしか無いが、放置すると再起動まで伸びる)。 */
    public void forget(UUID playerId) {
        if (playerId != null) {
            carry.remove(playerId);
        }
    }
}
