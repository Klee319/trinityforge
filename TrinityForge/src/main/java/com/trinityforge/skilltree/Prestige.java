package com.trinityforge.skilltree;

import java.util.Map;

/**
 * The prestige (level-cap breakthrough) reward block of a skill tree (SKILL_TREE design section A).
 * Like a node, it splits into TF-owned numeric {@code buffs} (canonical stat keys, applied by TF) and
 * ValhallaMMO {@code native} perk rewards. {@code name} and {@code effectText} may be {@code null}.
 *
 * <p>{@code maxTimes} is the configurable プレステージ上限回数: how many chained tiers ({@code ng1..ngN})
 * the generator fabricates from this single block, each requiring the previous and each carrying the same
 * {@code buffs}/{@code native} rewards. Values below 1 are normalized up to 1, so an unset/zero/negative
 * config value always yields the historical single-tier ({@code ng1}) behavior.
 */
public record Prestige(
        boolean enabled,
        int atLevel,
        String name,
        String effectText,
        Map<String, Double> buffs,
        Map<String, Double> mainhandBuffs,
        Map<String, Map<String, Double>> multipliers,
        Map<String, Map<String, Double>> mainhandMultipliers,
        Map<String, Object> native_,
        int maxTimes) {

    public Prestige {
        buffs = buffs == null ? Map.of() : Map.copyOf(buffs);
        mainhandBuffs = mainhandBuffs == null ? Map.of() : Map.copyOf(mainhandBuffs);
        multipliers = deepCopy(multipliers);
        mainhandMultipliers = deepCopy(mainhandMultipliers);
        native_ = native_ == null ? Map.of() : Map.copyOf(native_);
        maxTimes = Math.max(1, maxTimes);
    }

    /** Backward-compatible constructor for additive-only prestige definitions. */
    public Prestige(boolean enabled, int atLevel, String name, String effectText,
                    Map<String, Double> buffs, Map<String, Object> native_, int maxTimes) {
        this(enabled, atLevel, name, effectText, buffs, Map.of(), Map.of(), Map.of(), native_, maxTimes);
    }

    private static Map<String, Map<String, Double>> deepCopy(Map<String, Map<String, Double>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Map<String, Double>> copy = new java.util.LinkedHashMap<>();
        raw.forEach((layer, stats) -> copy.put(layer, stats == null ? Map.of() : Map.copyOf(stats)));
        return Map.copyOf(copy);
    }
}
