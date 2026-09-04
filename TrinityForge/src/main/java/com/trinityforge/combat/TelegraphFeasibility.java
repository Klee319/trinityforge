package com.trinityforge.combat;

/**
 * 予告つきの技が「本当に見てから避けられるか」を机上で検査する純関数（2026-09-04、Codex UXレビュー #6）。
 *
 * <p>詠唱秒数から予告演出の反応猶予（{@link #D95_SECONDS}、人間の反応時間の95パーセンタイルの仮置き）を
 * 引き、残りの時間でその状態の移動速度がどれだけ進めるかを「許容半径」とする。技の半径（型によって
 * 判定の実効サイズが違う）がこれを超えていたら、詠唱が終わるまでに逃げ切れない＝実質回避不能技になる。
 *
 * <p>{@link #D95_SECONDS} は<b>仮置き</b>。実測（プレイヤーが予告を見てから移動を開始するまでの
 * 反応時間の分布）が取れたら差し替えること。
 */
public final class TelegraphFeasibility {

    private TelegraphFeasibility() {
    }

    /** バニラの徒歩速度（ブロック/秒）。 */
    public static final double WALK_SPEED = 4.317;
    /** バニラのダッシュ速度（ブロック/秒）。 */
    public static final double SPRINT_SPEED = 5.612;
    /** 鈍足がかかった状態の速度（徒歩の半分）。 */
    public static final double SLOWED_SPEED = WALK_SPEED * 0.5;
    /** 盾を構えている間の速度（徒歩の0.4倍）。 */
    public static final double SHIELD_SPEED = WALK_SPEED * 0.4;

    /**
     * 予告を見てから実際に移動を開始するまでの反応時間（秒、95パーセンタイル）。
     *
     * <p><b>仮置き。</b>実測後に差し替える。
     */
    public static final double D95_SECONDS = 0.35;

    /** 反応時間ぶんを引いてもなお、判定境界ちょうどで詰む事故を避けるための安全余白（ブロック）。 */
    public static final double SAFETY_MARGIN = 0.5;

    /** 単一解型（{@code DELAYED_ZONE}/{@code FIXED_ZONE}）は判定形状が読みやすいので判定を緩める倍率。 */
    private static final double SINGLE_MARK_LENIENCY = 0.8;

    /**
     * 許容半径 = 状態別速度 × max(0, 詠唱秒 − D95) − 安全余白。
     *
     * <p>詠唱秒が D95 以下なら「反応する前に終わる」ので移動可能距離は 0 とみなし、
     * 許容半径は {@code -SAFETY_MARGIN}（＝どんな正の半径にも足りない）になる。
     */
    public static double allowedRadius(double speed, double castSeconds) {
        double usable = Math.max(0.0, castSeconds - D95_SECONDS);
        return speed * usable - SAFETY_MARGIN;
    }

    /**
     * その技が「見てから動けば避けられる」か。{@code castSeconds<=0}（予告なし）は
     * この検査の対象外として常に true を返す（予告なし技は別の設計判断で許容されている前提）。
     *
     * <p>型別の「実効半径」:
     * <ul>
     *   <li>{@code BEAM}: 太さの直径（{@code 2 * radius}）。線から横へずれる距離が本質だから。</li>
     *   <li>{@code DELAYED_ZONE} / {@code FIXED_ZONE}: 単一の固定点なので {@link #SINGLE_MARK_LENIENCY}
     *       倍だけ緩める（判定形状が読みやすく、外周を歩くだけで避けられるため）。</li>
     *   <li>それ以外の円形/床印: {@code radius} そのまま。</li>
     * </ul>
     */
    public static boolean walkable(MobAbility ability) {
        double castSeconds = ability.telegraphTicks() / 20.0;
        if (castSeconds <= 0.0) {
            return true;
        }
        double effectiveRadius = switch (ability.type()) {
            case BEAM -> 2.0 * ability.radius();
            case DELAYED_ZONE, FIXED_ZONE -> ability.radius() * SINGLE_MARK_LENIENCY;
            default -> ability.radius();
        };
        return allowedRadius(WALK_SPEED, castSeconds) >= effectiveRadius;
    }
}
