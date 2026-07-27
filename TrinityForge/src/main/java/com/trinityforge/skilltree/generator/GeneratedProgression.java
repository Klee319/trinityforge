package com.trinityforge.skilltree.generator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The full offline output of {@link SkillTreeProgressionGenerator} for one skill tree: the ValhallaMMO
 * {@code <skill>_progression.yml} text, the {@code lang} name/description map it references, and the
 * structured {@code perks} (keyed by ValhallaMMO perk id, in stable generation order) for assertions.
 *
 * <p>{@code perks} and {@code lang} preserve insertion order and are unmodifiable views. Writing these
 * to disk is the caller's responsibility; this record is a pure value.
 */
public record GeneratedProgression(
        String skill,
        String startingCoordinates,
        Map<String, GeneratedPerk> perks,
        Map<String, String> lang,
        String yaml) {

    public GeneratedProgression {
        perks = Collections.unmodifiableMap(new LinkedHashMap<>(perks));
        lang = Collections.unmodifiableMap(new LinkedHashMap<>(lang));
    }
}
