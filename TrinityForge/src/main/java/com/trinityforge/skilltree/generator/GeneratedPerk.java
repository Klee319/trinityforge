package com.trinityforge.skilltree.generator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One generated ValhallaMMO progression perk (SKILL_TREE design section 2). This is the structured,
 * offline-verifiable view the tests assert against; {@link ProgressionYaml} renders it (plus the
 * {@code <lang.*>} references derived from {@code nameKey}/{@code descriptionKey}) into the YAML text.
 *
 * <p>{@code perkRewards} carries only ValhallaMMO-native {@code native} entries plus, for an exclusive
 * greek node, the reciprocal {@code perks_locked_add} list — TF {@code buffs} are intentionally never
 * present here (they are applied by TF to avoid double application). Map order is preserved (insertion
 * order is significant for deterministic YAML) and every collection is an unmodifiable view.
 *
 * <p>{@code name}/{@code description} are the literal display strings the emitter writes into the perk's
 * {@code name}/{@code description} YAML fields. TrinityForge deploys the generated progression ymls into
 * ValhallaMMO's data folder from {@code onLoad} but never writes ValhallaMMO's shared language file, so
 * the display text is inlined rather than emitted as {@code <lang.*>} references (which would render raw
 * unless the language file were also merged). {@code nameKey}/{@code descriptionKey} are retained as the
 * stable lang-key identifiers (used by the offline {@code lang} map / tests); ValhallaMMO's
 * {@code TranslationManager.translatePlaceholders} passes a literal string through unchanged.
 */
public record GeneratedPerk(
        String id,
        String icon,
        Coord coords,
        int requiredLv,
        int cost,
        boolean hidden,
        List<String> requirePerkAll,
        List<String> requirePerkOne,
        Map<String, Object> perkRewards,
        Map<String, Map<String, Object>> connectionLine,
        String name,
        String description,
        String nameKey,
        String descriptionKey) {

    public GeneratedPerk {
        requirePerkAll = List.copyOf(requirePerkAll);
        requirePerkOne = List.copyOf(requirePerkOne);
        perkRewards = Collections.unmodifiableMap(new LinkedHashMap<>(perkRewards));
        connectionLine = Collections.unmodifiableMap(new LinkedHashMap<>(connectionLine));
    }
}
