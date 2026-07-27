package com.trinityforge.stats;

import java.util.Locale;
import java.util.Objects;

/**
 * Closed vocabulary for {@code stats/lore.yml stats.<key>.limits.stacking}: how multiple sources of the
 * same stat combine. Mirrors {@code tools/config-editor/lib/schema.js}.
 */
public enum StatStacking {
    ADDITIVE,
    MULTIPLICATIVE,
    MAX_ONLY;

    public static StatStacking parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown limits.stacking '" + raw + "'", ex);
        }
    }
}
