package com.trinityforge.combat;

/**
 * Attacker-side inputs for one damage component (COMBAT_SYSTEM_SPEC 2.1 / 3.1).
 * Percent values are fractions: 0.20 == +20% / 20%.
 *
 * @param defaultDamage      default damage for this component (already level-scaled for physical)
 * @param flatBonusDamage    固定追加ダメージ (flat additive, step 1)
 * @param percentBonusDamage 割合追加ダメージ% (of defaultDamage, folded into base at step 1); 負値も
 *                           許容(ロール下限が負のアイテムが存在するため) — 下限は自身では持たず、
 *                           step-7 の minClamp が自然な床になる
 * @param critChance         会心率 (step 3); negative values are retained and naturally never proc
 * @param critDamage         会心ダメージ% extra on crit (step 3)
 * @param penetration        貫通率%; negative values make 防御率 more effective (step 4)
 * @param damageModifier     ダメージ補正 endpoint vs 100% (step 6): each attack rolls
 *                           Uniform[min(1,endpoint), max(1,endpoint)]; unset → 1.0, explicit 0/negative
 *                           values remain real endpoints
 * @param fixedDamage        固定ダメージ, 全防御ステータスを貫通する純加算 (step 8)
 */
public record AttackStats(
        double defaultDamage,
        double flatBonusDamage,
        double percentBonusDamage,
        double critChance,
        double critDamage,
        double penetration,
        double damageModifier,
        double fixedDamage
) {
    /**
     * Guards every construction path (config, PDC-derived bridge, tests) so a bad input can never
     * reach the pipeline: non-finite values collapse to 0 (damageModifier uses its neutral 1.0).
     * Positive probability/rate overflow is capped at 1, while negative authored values are retained. Penetration above 1 would flip the
     * step-4 defense-rate term ({@code 1 - defenseRate * (1 - penetration)}) into an amplifier
     * instead of a mitigator once penetration exceeds 1.0.
     */
    public AttackStats {
        defaultDamage = finiteOrZero(defaultDamage);
        flatBonusDamage = finiteOrZero(flatBonusDamage);
        percentBonusDamage = finiteOrZero(percentBonusDamage);
        critChance = capAtOne(finiteOrZero(critChance));
        critDamage = finiteOrZero(critDamage);
        penetration = capAtOne(finiteOrZero(penetration));
        damageModifier = Double.isFinite(damageModifier) ? damageModifier : 1.0;
        fixedDamage = finiteOrZero(fixedDamage);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double capAtOne(double value) {
        return Math.min(1.0, value);
    }

    /** A pure default-damage attack with no extra stats (e.g. vanilla mob baseline). */
    public static AttackStats plain(double defaultDamage) {
        return new AttackStats(defaultDamage, 0, 0, 0, 0, 0, 1, 0);
    }

    /**
     * Returns a copy with {@code defaultDamage} replaced, keeping every other stat. Lets a caller
     * build the attacker stats (crit/penetration/... from a catalyst) once and then inject the
     * level-scaled default damage the pipeline should use.
     */
    public AttackStats withDefaultDamage(double newDefaultDamage) {
        return new AttackStats(newDefaultDamage, flatBonusDamage, percentBonusDamage, critChance,
                critDamage, penetration, damageModifier, fixedDamage);
    }
}
