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

    /**
     * プレイヤー向け日本語ラベル。この enum が正本で、{@code tools/config-editor/lib/
     * lore-declaration-vocabulary.js} の {@code APPLIES_TO_LABELS} はこの switch のミラー
     * ({@link StatTriggerWhen#label()} と同じ規約)。
     */
    public String label() {
        return switch (this) {
            case PLAYER -> "プレイヤー";
            case MOB -> "モブ";
        };
    }
}
