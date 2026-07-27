package com.trinityforge.stats;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per equipment-category fallback overlay from {@code stats/item-stats.yml} {@code fallback.<cat>}.
 * Only {@code fixed} values are used at runtime; {@code loreDefaultKeys} marks which keys force
 * lore visibility (デフォルト表示) even when hide-when-zero would hide them.
 */
public record FallbackCategoryProfile(Map<String, Double> fixed, Set<String> loreDefaultKeys) {

    public static final FallbackCategoryProfile EMPTY =
            new FallbackCategoryProfile(Map.of(), Set.of());

    public FallbackCategoryProfile {
        Map<String, Double> fixedCopy = new LinkedHashMap<>();
        if (fixed != null) {
            fixed.forEach((k, v) -> {
                if (k != null && v != null && Double.isFinite(v)) {
                    fixedCopy.put(StatKeys.canonical(k), v);
                }
            });
        }
        fixed = Collections.unmodifiableMap(fixedCopy);
        Set<String> loreCopy = new LinkedHashSet<>();
        if (loreDefaultKeys != null) {
            for (String k : loreDefaultKeys) {
                if (k != null && !k.isBlank()) {
                    loreCopy.add(StatKeys.canonical(k));
                }
            }
        }
        loreDefaultKeys = Collections.unmodifiableSet(loreCopy);
    }

    public boolean isEmpty() {
        return fixed.isEmpty() && loreDefaultKeys.isEmpty();
    }

    public boolean loreDefault(String statKey) {
        return loreDefaultKeys.contains(StatKeys.canonical(Objects.requireNonNull(statKey, "statKey")));
    }
}
