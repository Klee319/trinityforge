package com.trinityforge.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Classifies each stat key on an item as fixed, per-quality, or random-roll sourced.
 */
public final class StatSourceResolver {

    private StatSourceResolver() {
    }

    public static Map<String, StatSource> resolve(ItemStatProfile profile, int qualityLevel,
                                                   long rollSeed, QualityRollModel rollModel,
                                                   Set<String> granted) {
        Map<String, StatSource> sources = new LinkedHashMap<>();
        if (profile == null) {
            return sources;
        }
        profile.fixed().keySet().forEach(key -> putIfGranted(sources, key, StatSource.FIXED, granted));
        profile.perQuality().keySet().forEach(key -> {
            if (granted == null || granted.contains(key)) {
                sources.put(StatKeys.canonical(key), StatSource.PER_QUALITY);
            }
        });
        if (rollModel != null) {
            profile.random().keySet().forEach(key -> {
                if (granted == null || granted.contains(key)) {
                    sources.put(StatKeys.canonical(key), StatSource.RANDOM);
                }
            });
        }
        return sources;
    }

    private static void putIfGranted(Map<String, StatSource> sources, String key, StatSource source,
                                     Set<String> granted) {
        if (granted == null || granted.contains(key)) {
            sources.put(StatKeys.canonical(key), source);
        }
    }
}
