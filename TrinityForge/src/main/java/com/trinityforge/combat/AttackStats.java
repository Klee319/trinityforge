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
 * @param magicRatio         このモブの通常攻撃のうち魔法として解決する割合 [0,1](2026-08-02 新設)。
 *                           0.0(既定)= 完全物理(従来どおり)、1.0 = 完全魔法、中間値は
 *                           {@link com.trinityforge.combat.SymmetricCombatService} が物理/魔法の
 *                           2コンポーネントに分割し、回避ロールは1回のまま両方へ通す(hybrid攻撃)。
 *                           プレイヤー側の武器・アイテムの {@code AttackStats} には意味を持たない
 *                           (モブの通常攻撃専用)。
 */
public record AttackStats(
        double defaultDamage,
        double flatBonusDamage,
        double percentBonusDamage,
        double critChance,
        double critDamage,
        double penetration,
        double damageModifier,
        double fixedDamage,
        double magicRatio
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
        magicRatio = Math.max(0.0, Math.min(1.0, finiteOrZero(magicRatio)));
    }

    /**
     * Back-compat 8-arg constructor (pre-2026-08-02 shape): every existing call site in the codebase
     * (player weapons, mob-types/mob-profiles importers, PDC bridges) constructs an {@link AttackStats}
     * with these 8 fields and has no notion of {@link #magicRatio()} yet. Delegates to the canonical
     * 9-arg constructor with {@code magicRatio = 0.0} (完全物理、従来どおり) so none of those call
     * sites need to change.
     */
    public AttackStats(double defaultDamage, double flatBonusDamage, double percentBonusDamage,
                        double critChance, double critDamage, double penetration, double damageModifier,
                        double fixedDamage) {
        this(defaultDamage, flatBonusDamage, percentBonusDamage, critChance, critDamage, penetration,
                damageModifier, fixedDamage, 0.0);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double capAtOne(double value) {
        return Math.min(1.0, value);
    }

    /** A pure default-damage attack with no extra stats (e.g. vanilla mob baseline). */
    public static AttackStats plain(double defaultDamage) {
        return new AttackStats(defaultDamage, 0, 0, 0, 0, 0, 1, 0, 0.0);
    }

    /**
     * Returns a copy with {@code defaultDamage} replaced, keeping every other stat (including
     * {@link #magicRatio()}). Lets a caller build the attacker stats (crit/penetration/... from a
     * catalyst) once and then inject the level-scaled default damage the pipeline should use.
     */
    public AttackStats withDefaultDamage(double newDefaultDamage) {
        return new AttackStats(newDefaultDamage, flatBonusDamage, percentBonusDamage, critChance,
                critDamage, penetration, damageModifier, fixedDamage, magicRatio);
    }

    /** Returns a copy with {@link #magicRatio()} replaced, keeping every other stat. */
    public AttackStats withMagicRatio(double newMagicRatio) {
        return new AttackStats(defaultDamage, flatBonusDamage, percentBonusDamage, critChance,
                critDamage, penetration, damageModifier, fixedDamage, newMagicRatio);
    }
}
