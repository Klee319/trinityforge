package com.trinityforge.combat;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * player → player のダメージだけに掛かる抑制。
 *
 * <p><b>2026-07-27 新設の背景</b>: TF のダメージ式には PvP 用の分岐が
 * {@code aoe.hit-players}(AoEが他プレイヤーを巻き込むか)しか無く、
 * <b>モブ向けに調整された値がそのまま player→player に乗っていた</b>。
 * モブ側は HP 150×1.066^L で釣り合わせている一方、プレイヤーの最大体力はバニラ20＋power ツリー(+10)＋
 * プレステージで概ね<b>33が上限</b>。対して Lv100 帯のプレイヤー攻撃力は約1052 あるため、
 * <b>先に当てた側が確定で即死させる</b>状態だった。
 *
 * <p><b>なぜ倍率だけでなく「最大体力に対する割合上限」も置くのか</b>: 根本原因は
 * 「攻撃力は指数で伸びるのに、プレイヤーの体力はほぼ一定」という構造にある。単純な倍率だけだと
 * 攻撃カーブを触るたびに PvP 用の倍率も調整し直さなければならず、<b>調整漏れがそのまま即死ゲーに戻る</b>。
 * 「1発で最大体力の何%まで」という上限はスケールフリーなので、攻撃側がどれだけ伸びても
 * 「PvPは最低◯発かかる」が構造的に保証される。倍率は上限に届かない低レベル帯の手触り調整用。
 *
 * <p>負のダメージ(=回復。{@code defense.max-rate} を1超にすると起こりうる)は素通しする —
 * PvP係数で回復量を削るのは意味が通らないため。
 */
public final class PvpDamagePolicy {

    private PvpDamagePolicy() {
    }

    /**
     * 純関数。Bukkit 非依存なので単体テスト可能。
     *
     * @param damage                 抑制前のダメージ
     * @param victimMaxHealth        被弾者の最大体力({@code <= 0} なら割合上限は掛けない)
     * @param enabled                {@code pvp.enabled}
     * @param multiplier             {@code pvp.damage-multiplier}
     * @param maxPercentOfMaxHealth  {@code pvp.max-damage-percent-of-max-health}(0 = 上限なし)
     */
    public static double apply(double damage, double victimMaxHealth,
                               boolean enabled, double multiplier, double maxPercentOfMaxHealth) {
        if (!enabled || damage <= 0.0) {
            return damage;
        }
        double scaled = damage * Math.max(0.0, multiplier);
        if (maxPercentOfMaxHealth > 0.0 && victimMaxHealth > 0.0) {
            scaled = Math.min(scaled, victimMaxHealth * maxPercentOfMaxHealth);
        }
        return scaled;
    }

    /**
     * 被弾者の最大体力。属性が読めない環境(テストダブル等)では {@code 0} を返し、
     * 呼び出し先で<b>割合上限だけが無効化され倍率は生きる</b>ようにする(安全側)。
     */
    public static double maxHealthOf(LivingEntity victim) {
        if (victim == null) {
            return 0.0;
        }
        try {
            var attribute = victim.getAttribute(Attribute.MAX_HEALTH);
            return attribute == null ? 0.0 : Math.max(0.0, attribute.getValue());
        } catch (RuntimeException | NoSuchFieldError | NoClassDefFoundError ignored) {
            return 0.0;
        }
    }

    /** PvP(攻撃者もプレイヤー、被弾者もプレイヤー)か。攻撃者側は呼び出し元で既にPlayer確定。 */
    public static boolean isPvp(Object victim) {
        return victim instanceof Player;
    }
}
