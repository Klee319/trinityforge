package com.trinityforge.stats;

import java.util.Locale;
import java.util.Objects;

/**
 * Closed vocabulary for {@code stats/lore.yml stats.<key>.trigger.applies-to}: which entity kinds the
 * stat is meaningful for. Mirrors {@code tools/config-editor/lib/schema.js}.
 */
public enum StatAppliesTo {
    PLAYER,
    MOB;

    public static StatAppliesTo parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown trigger.applies-to '" + raw + "'", ex);
        }
    }
}
