package com.trinityforge.stats;

/**
 * 討伐EXPの「最大HPに応じた項」の算出（2026-08-19 ユーザー要望）。
 *
 * <p><b>なぜ指数が必要か</b>: 討伐EXPは {@code base + per-mob-level*Lv + per-max-health*最大HP} で、
 * 最大HP項が<b>全帯で支配的</b>（Lv10 のエンダーマンでも HP項 353 対 base+Lv項 40）。
 * ところがモブの最大HPは {@code combat/mob-types.yml} で<b>指数成長 + Lv45 以降の加算区間</b>を
 * 持つので、HP項が線形だと討伐EXPもそのまま指数で伸びる（Lv80 で Lv10 の約88倍）。
 * <b>ここで {@code per-max-health} を単純に下げると序盤も同じ比率で下がってしまう</b> ——
 * 序盤の上がり方を保ったまま高帯だけ寝かせるには、係数ではなく<b>HPに対する指数</b>を動かすしかない。
 *
 * <p>式: {@code HP項 = perMaxHealth * min(H, anchor * (H/anchor)^exponent)}。
 * <ul>
 *   <li>{@code H <= anchor} の帯は {@code min} が線形側を選ぶので<b>1ミリも変わらない</b>
 *       （指数側は H&lt;anchor で線形より大きくなるため、min を取らないと序盤が増えてしまう）。</li>
 *   <li>{@code H > anchor} では {@code (H/anchor)^(exponent-1)} 倍に圧縮される。</li>
 *   <li>{@code anchor <= 0} または {@code exponent >= 1} は<b>従来どおりの線形</b>（後方互換）。</li>
 * </ul>
 */
public final class KillExpHealthTerm {

    private KillExpHealthTerm() {
    }

    /**
     * @param perMaxHealth 最大HP1あたりのEXP（{@code per-max-health}）
     * @param maxHealth    倒したモブの最大HP。0以下・非有限は 0 として扱う
     * @param anchor       圧縮を始めるHP（{@code per-max-health-anchor}）。0以下で無効＝線形
     * @param exponent     HPに掛ける指数（{@code per-max-health-exponent}）。1.0以上で無効＝線形
     */
    public static double healthTerm(double perMaxHealth, double maxHealth,
                                    double anchor, double exponent) {
        if (!Double.isFinite(perMaxHealth) || perMaxHealth <= 0.0) {
            return 0.0;
        }
        double health = Double.isFinite(maxHealth) ? Math.max(0.0, maxHealth) : 0.0;
        if (health <= 0.0) {
            return 0.0;
        }
        if (!Double.isFinite(anchor) || anchor <= 0.0
                || !Double.isFinite(exponent) || exponent >= 1.0 || exponent <= 0.0) {
            return perMaxHealth * health;
        }
        double compressed = anchor * Math.pow(health / anchor, exponent);
        if (!Double.isFinite(compressed)) {
            return perMaxHealth * health;
        }
        return perMaxHealth * Math.min(health, compressed);
    }
}
