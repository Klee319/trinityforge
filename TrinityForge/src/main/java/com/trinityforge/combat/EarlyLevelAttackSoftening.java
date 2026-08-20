package com.trinityforge.combat;

/**
 * 序盤(低レベル帯)モブの火力緩和倍率(2026-07-28、{@code combat/damage.yml} の
 * {@code early-level-attack})。
 *
 * <p>{@code combat/mob-types.yml} の attack-power 指数カーブ(base × 1.033^Lv)自体は触らず、
 * モブ→プレイヤーの基本ダメージにだけ後掛けする。base を直接下げると全レベル帯が下がり、
 * 中盤以降の校正(同帯装備で約10発耐える)がやり直しになるため、「序盤だけ」を独立した
 * つまみとして分離してある。
 *
 * <p>Bukkitに依存しない純粋関数として切り出してあるのは、{@link SymmetricCombatService} 本体が
 * config/defense-resolver など5つの依存を要求してユニットテストしづらいため。
 */
public final class EarlyLevelAttackSoftening {

    private EarlyLevelAttackSoftening() {
    }

    /**
     * {@code multiplier(L) = L >= untilLevel ? 1.0 : m0 + (1 - m0) * L / untilLevel}。
     *
     * <p>無効化条件(いずれかで 1.0 = 緩和なし): {@code enabled == false} /
     * {@code untilLevel <= 0} / {@code level0Multiplier} が非有限または 1.0 以上。
     * {@code level0Multiplier} の負値は 0.0 へ、{@code mobLevel} の負値は 0 へ丸める。
     */
    public static double multiplier(boolean enabled, int untilLevel, double level0Multiplier, int mobLevel) {
        if (!enabled || untilLevel <= 0 || !Double.isFinite(level0Multiplier) || level0Multiplier >= 1.0) {
            return 1.0;
        }
        int level = Math.max(0, mobLevel);
        if (level >= untilLevel) {
            return 1.0;
        }
        double base = Math.max(0.0, level0Multiplier);
        return base + (1.0 - base) * ((double) level / untilLevel);
    }
}
