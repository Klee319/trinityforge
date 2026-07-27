package com.trinityforge.stats;

import java.util.Locale;
import java.util.Objects;

/**
 * Closed vocabulary for {@code stats/lore.yml stats.<key>.trigger.sources}: declares <b>where</b> the
 * stat's contributing value is aggregated from. Mirrors {@code tools/config-editor/lib/schema.js}.
 */
public enum StatSourceScope {
    ALL,
    MAINHAND_ONLY,
    WORN_ARMOR_ONLY,
    OFFHAND_OPT_IN,
    NON_ITEM_ONLY;

    public static StatSourceScope parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown trigger.sources '" + raw + "'", ex);
        }
    }
}
