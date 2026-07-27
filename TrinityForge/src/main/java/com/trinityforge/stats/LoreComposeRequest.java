package com.trinityforge.stats;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Inputs for categorized item lore beyond the bare stats map.
 *
 * <p>{@code forceShowKeys} are canonical stat keys that bypass {@code hide-when-zero}
 * (item-stats category fallback の「デフォルト表示」ON).
 *
 * <p>{@code chanceKeys} are canonical stat keys that have a grant-chance configured
 * (item-stats {@code grant-chances}) — they may use the advanced chance colors from
 * {@link LoreColorRules}.
 *
 * <p>{@code multipliers} are 乗算モードのステ: layer id → (canonical stat key → multiplier value,
 * e.g. 1.2 = x1.2). Rendered as {@code x1.2} lines (sign 'x', no unit) right after the additive
 * line of the same stat, ordered by the layer order in {@code stats/lore.yml multiplier-layers}.
 */
public record LoreComposeRequest(
        Map<String, Double> stats,
        Map<String, StatSource> statSources,
        String qualityTierName,
        int qualityScore,
        Integer durabilityCurrent,
        Integer durabilityMax,
        String useSkillDisplayName,
        int useLevelRequirement,
        Set<String> forceShowKeys,
        Set<String> chanceKeys,
        Map<String, Map<String, Double>> multipliers,
        String qualityTierColor) {

    public LoreComposeRequest {
        stats = Map.copyOf(Objects.requireNonNull(stats, "stats"));
        statSources = statSources == null ? Map.of() : Map.copyOf(statSources);
        qualityTierName = qualityTierName == null ? "" : qualityTierName;
        qualityTierColor = qualityTierColor == null ? "" : qualityTierColor;
        useSkillDisplayName = useSkillDisplayName == null ? "" : useSkillDisplayName;
        forceShowKeys = forceShowKeys == null ? Set.of() : Set.copyOf(forceShowKeys);
        chanceKeys = chanceKeys == null ? Set.of() : Set.copyOf(chanceKeys);
        multipliers = multipliers == null ? Map.of() : Map.copyOf(multipliers);
        if (useLevelRequirement < 0) {
            useLevelRequirement = 0;
        }
    }

    /** Back-compat without qualityTierColor (tier name is rendered without a color tag). */
    public LoreComposeRequest(Map<String, Double> stats,
                              Map<String, StatSource> statSources,
                              String qualityTierName,
                              int qualityScore,
                              Integer durabilityCurrent,
                              Integer durabilityMax,
                              String useSkillDisplayName,
                              int useLevelRequirement,
                              Set<String> forceShowKeys,
                              Set<String> chanceKeys,
                              Map<String, Map<String, Double>> multipliers) {
        this(stats, statSources, qualityTierName, qualityScore,
                durabilityCurrent, durabilityMax, useSkillDisplayName, useLevelRequirement,
                forceShowKeys, chanceKeys, multipliers, "");
    }

    /** Back-compat without chanceKeys/multipliers. */
    public LoreComposeRequest(Map<String, Double> stats,
                              Map<String, StatSource> statSources,
                              String qualityTierName,
                              int qualityScore,
                              Integer durabilityCurrent,
                              Integer durabilityMax,
                              String useSkillDisplayName,
                              int useLevelRequirement,
                              Set<String> forceShowKeys) {
        this(stats, statSources, qualityTierName, qualityScore,
                durabilityCurrent, durabilityMax, useSkillDisplayName, useLevelRequirement,
                forceShowKeys, Set.of(), Map.of(), "");
    }

    public LoreComposeRequest(Map<String, Double> stats,
                              Map<String, StatSource> statSources,
                              String qualityTierName,
                              int qualityScore,
                              Integer durabilityCurrent,
                              Integer durabilityMax,
                              String useSkillDisplayName,
                              int useLevelRequirement) {
        this(stats, statSources, qualityTierName, qualityScore,
                durabilityCurrent, durabilityMax, useSkillDisplayName, useLevelRequirement,
                Set.of(), Set.of(), Map.of(), "");
    }

    public LoreComposeRequest(Map<String, Double> stats,
                              Map<String, StatSource> statSources,
                              String qualityTierName,
                              int qualityScore,
                              Integer durabilityValue,
                              String useSkillDisplayName,
                              int useLevelRequirement) {
        this(stats, statSources, qualityTierName, qualityScore,
                durabilityValue, durabilityValue, useSkillDisplayName, useLevelRequirement,
                Set.of(), Set.of(), Map.of(), "");
    }
}
