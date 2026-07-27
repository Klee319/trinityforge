package com.trinityforge.combat;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Computes the damage of a single component through the unified 8-step pipeline
 * (COMBAT_SYSTEM_SPEC 2.1). Pure and stateless: the crit outcome is decided by the caller
 * so this stays deterministic and unit-testable.
 */
public final class ComponentDamageCalculator {

    private ComponentDamageCalculator() {
    }

    /**
     * @param attack    attacker stats for this component
     * @param defense   defender stats for this component
     * @param crit      whether the crit landed (step 3)
     * @param minClamp  step-7 floor (a component never drops below this)
     * @return final damage for this component (>= 0)
     */
    public static double compute(AttackStats attack, DefenseStats defense, boolean crit, double minClamp) {
        return compute(attack, defense, crit, minClamp, ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Same pipeline as {@link #compute(AttackStats, DefenseStats, boolean, double)} with a fixed
     * {@code unitRandom} in {@code [0, 1]} for the step-6 damage-modifier roll (deterministic tests).
     *
     * @param unitRandom uniform sample for damage-modifier endpoint interpolation
     */
    public static double compute(
            AttackStats attack, DefenseStats defense, boolean crit, double minClamp, double unitRandom) {
        // 1. base = デフォルトダメージ + 固定追加ダメージ + デフォルトダメージ × 割合追加ダメージ%
        //    (COMBAT_SYSTEM_SPEC 2.1 step1). percentBonusDamage may be negative (some rolls have a
        //    negative floor); base may go negative here. There is no zero-clamp at this point (nor
        //    at step2 below) — the pipeline's only floor is step7's minClamp.
        double base = attack.defaultDamage()
                + attack.flatBonusDamage()
                + attack.defaultDamage() * attack.percentBonusDamage();

        // 2. flat armor: 守備力(typed) を減算段の最初に前倒しする (COMBAT_SYSTEM_SPEC 2.1 step2 / 2.4)。
        //    守備力をここに置く理由: 会心の後段に置くと守備力が会心倍率で希釈され、防具強度
        //    (会心軽減率)と役割が重複してしまう。前段に置くことで「守備力＝通常/会心を問わず
        //    等しく削る値」「防具強度＝会心の上乗せ分だけを追加で削る値」という役割分離ができる。
        //    さらに、この後の %軽減(防御率/耐性/ダメージ補正)がすべて守備力減算後の値に掛かるため、
        //    守備力が %軽減で希釈されるのを防ぎ、被ダメージが極小の床値へ張り付く問題を解消する。
        //    この減算で base は負になり得るが、ここでは意図的に0クランプしない。負の base は
        //    そのまま次段(会心/%軽減)を素通りし、step7 の minClamp まで届く。これは
        //    physical.min-component-damage を負値に運用する構成(#6 Part B: 最終ダメージが負なら
        //    被害者をその絶対値分だけ回復させる)を成立させるために必須で、パイプライン全体を通して
        //    唯一の床は step7 である。
        //    defense.min-flat に負値を設定すると「防具が被ダメを増幅する」逆運用も可能(javadoc既存仕様)。
        //    守備力が負のとき base -= flat は base を増加させる方向にのみ働き、この経路には
        //    途中クランプが無いのでそのまま正しく反映される。
        double flat = defense.flatDefense();
        base -= flat;

        // 3. crit: base ×= (1 + 会心ダメージ% × (1 - 防具強度[会心軽減率])).
        //    負値は呪い/ギャンブル装備の正式な値として保持する。最終結果の唯一の床は step 7 の
        //    min-component-damage であり、そこを負に設定すれば会心を含む中間値も回復へ到達できる。
        if (crit) {
            base *= (1 + attack.critDamage() * (1 - Math.min(1.0, defense.armorStrength())));
        }

        // 4. defense rate (penetrable)
        base *= (1 - defense.defenseRate() * (1 - attack.penetration()));

        // 5. resistance (NOT penetrable)
        base *= (1 - defense.resistance());

        // 6. attacker damage modifier (Uniform[min(1,endpoint), max(1,endpoint)]), then defender reduction
        base *= rollDamageModifierMultiplier(attack.damageModifier(), unitRandom);
        base *= (1 - defense.damageReduction());

        // 7. clamp floor (per component)
        base = Math.max(base, minClamp);

        // 8. fixed damage: 全ての防御ステータス(守備力/防御率/耐性/ダメージ軽減/床)を貫通する純加算。
        //    旧仕様の「flatが削った分だけ還付する」意味論は廃止し、無条件で base に加算する。
        base += attack.fixedDamage();

        return base;
    }

    /**
     * Rolls a per-attack multiplier from a damage-modifier endpoint vs 100%.
     * {@code endpoint == 1.0} yields a flat {@code ×1.0}. Zero and negative endpoints are explicit
     * authored values; an unset stat is converted to the neutral endpoint by the input bridge.
     */
    static double rollDamageModifierMultiplier(double rawModifier, double unitRandom) {
        double endpoint = normalizeDamageModifierEndpoint(rawModifier);
        double lo = Math.min(1.0, endpoint);
        double hi = Math.max(1.0, endpoint);
        return lo + (hi - lo) * unitRandom;
    }

    static double normalizeDamageModifierEndpoint(double value) {
        if (value > 1.0 && value <= 100.0 && value == Math.floor(value)) {
            return value / 100.0;
        }
        return value;
    }
}
