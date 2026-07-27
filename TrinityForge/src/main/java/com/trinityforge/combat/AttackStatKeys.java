package com.trinityforge.combat;

/**
 * Fixed stat-key names that tell the weapon-PDC bridge which entry of the derived stat map feeds
 * which {@link AttackStats} field (COMBAT_SYSTEM_SPEC 3.1).
 *
 * <p><b>2026-07-25 (CMB-31):</b> these names are NOT config-driven despite the class previously
 * being documented as such. {@code combat/damage.yml}'s old {@code attack-stat-keys.*} block let an
 * operator override each name, but every schema default, shipped yml value, and hardcoded name here
 * were identical, and renaming one would silently do nothing because none of the following actually
 * follow a renamed key: {@code PercentStatNormalize}, {@code StatVocabulary},
 * {@code StatCategoryInference}, and the config editor's stat-key suggestions. The names are
 * effectively fixed; if they ever need to change, all 4 of those places must be updated together with
 * this class and {@code stats/roll.yml}.
 *
 * <p>{@code defaultDamage} is intentionally absent: it is never PDC-derived, it comes from the
 * level-scaled vanilla/spell base damage ({@code DefaultDamageResolver}).
 */
public record AttackStatKeys(
        String flatBonusDamage,
        String percentBonusDamage,
        String critChance,
        String critDamage,
        String penetration,
        String damageModifier,
        String fixedDamage
) {
    public static final String FLAT_BONUS_DAMAGE = "flat-bonus-damage";
    public static final String PERCENT_BONUS_DAMAGE = "percent-bonus-damage";
    public static final String CRIT_CHANCE = "crit-chance";
    public static final String CRIT_DAMAGE = "crit-damage";
    public static final String PENETRATION = "penetration";
    public static final String DAMAGE_MODIFIER = "damage-modifier";
    public static final String FIXED_DAMAGE = "fixed-damage";

    /** The single fixed instance every caller resolves; see the class javadoc for why this is fixed. */
    public static final AttackStatKeys DEFAULT = new AttackStatKeys(
            FLAT_BONUS_DAMAGE, PERCENT_BONUS_DAMAGE, CRIT_CHANCE, CRIT_DAMAGE,
            PENETRATION, DAMAGE_MODIFIER, FIXED_DAMAGE);
}
