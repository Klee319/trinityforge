package com.trinityforge.progression.catalog;

import com.trinityforge.progression.core.XpCurve;

import java.util.Map;
import java.util.Objects;

/**
 * Parsed representation of the {@code experience} section of one
 * {@code skills/base/*_progression.yml} resource.
 *
 * @param skillId       uppercase skill ID (e.g. {@code "MINING"})
 * @param maxLevel      maximum reachable level from {@code experience.max_level}
 * @param formulaString the raw formula string from {@code experience.exp_level_curve}
 * @param curve         evaluable EXP cost function built from {@code formulaString}
 * @param actionExp     nested material tables as {@code action.MATERIAL → amount}
 * @param rates         scalar producer rates ({@code durability_tools_exp_multiplier_stack}, …)
 */
public record SkillCatalogEntry(
        String skillId,
        int maxLevel,
        String formulaString,
        XpCurve curve,
        Map<String, Double> actionExp,
        Map<String, Double> rates
) {
    public SkillCatalogEntry {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(formulaString, "formulaString");
        Objects.requireNonNull(curve, "curve");
        actionExp = actionExp == null ? Map.of() : Map.copyOf(actionExp);
        rates = rates == null ? Map.of() : Map.copyOf(rates);
        if (maxLevel <= 0) {
            throw new IllegalArgumentException("maxLevel must be > 0: " + maxLevel);
        }
    }

    /** Backward-compatible ctor used by older tests. */
    public SkillCatalogEntry(String skillId, int maxLevel, String formulaString, XpCurve curve,
                             Map<String, Double> actionExp) {
        this(skillId, maxLevel, formulaString, curve, actionExp, Map.of());
    }

    public double expFor(String action, String material) {
        if (action == null || material == null) return 0.0;
        return actionExp.getOrDefault(action + "." + material, 0.0);
    }

    public double rate(String key, double defaultValue) {
        if (key == null) return defaultValue;
        Double value = rates.get(key);
        return value == null || !Double.isFinite(value) ? defaultValue : value;
    }
}
