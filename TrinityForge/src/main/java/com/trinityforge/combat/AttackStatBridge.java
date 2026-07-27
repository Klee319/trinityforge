package com.trinityforge.combat;

import com.trinityforge.stats.StatKeys;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure mapping from a weapon's derived stats (stat key -&gt; value, produced by {@code
 * stats/item-stats.yml} via {@code DerivedItemStats}) to {@link AttackStats} fields, using the
 * fixed key names in {@link AttackStatKeys} (COMBAT_SYSTEM_SPEC 3.1; 2026-07-25 CMB-31 — no longer
 * config-driven, see {@link AttackStatKeys} javadoc for why).
 *
 * <p>Both the derived-stat keys and the configured key names are normalized through
 * {@link StatKeys#canonical} before comparison, so kebab-case ({@code stats/roll.yml}) and
 * snake_case spellings resolve identically (gap I1). A stat with no matching key contributes 0,
 * matching the existing "PDC-less item stays at baseline" behaviour.
 *
 * <p>{@code defaultDamage} is left at 0; the caller injects the level-scaled value afterward via
 * {@link AttackStats#withDefaultDamage}. Damage modifier is the sole neutral-at-one stat: an absent
 * key maps to {@code 1.0}, while an explicitly authored {@code 0} or negative value is preserved.
 */
public final class AttackStatBridge {

    private AttackStatBridge() {
    }

    public static AttackStats bridge(Map<String, Double> derivedStats, AttackStatKeys keys) {
        Objects.requireNonNull(derivedStats, "derivedStats");
        Objects.requireNonNull(keys, "keys");
        Map<String, Double> canonical = canonicalize(derivedStats);
        return new AttackStats(
                0.0,
                valueFor(canonical, keys.flatBonusDamage()),
                valueFor(canonical, keys.percentBonusDamage()),
                valueFor(canonical, keys.critChance()),
                valueFor(canonical, keys.critDamage()),
                valueFor(canonical, keys.penetration()),
                valueFor(canonical, keys.damageModifier(), 1.0),
                valueFor(canonical, keys.fixedDamage()));
    }

    private static Map<String, Double> canonicalize(Map<String, Double> raw) {
        Map<String, Double> canonical = new HashMap<>();
        raw.forEach((key, value) -> canonical.put(StatKeys.canonical(key), value));
        return canonical;
    }

    private static double valueFor(Map<String, Double> canonical, String configuredKey) {
        return valueFor(canonical, configuredKey, 0.0);
    }

    private static double valueFor(Map<String, Double> canonical, String configuredKey, double fallback) {
        return canonical.getOrDefault(StatKeys.canonical(configuredKey), fallback);
    }
}
