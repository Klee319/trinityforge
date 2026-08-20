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

    /**
     * プレイヤー向け日本語ラベル。この enum が正本で、{@code tools/config-editor/lib/
     * lore-declaration-vocabulary.js} の {@code SOURCE_SCOPE_LABELS} はこの switch のミラー
     * ({@link StatTriggerWhen#label()} と同じ規約)。
     */
    public String label() {
        return switch (this) {
            case ALL -> "装備・パーク・アドオン等すべての合算元が対象";
            case MAINHAND_ONLY -> "メインハンドの装備のみが対象";
            case WORN_ARMOR_ONLY -> "装着中の防具のみが対象";
            case OFFHAND_OPT_IN -> "オフハンドも対象に含められる(任意)";
            case NON_ITEM_ONLY -> "アイテム以外の合算元のみが対象(パーク/アドオン等)";
        };
    }
}
