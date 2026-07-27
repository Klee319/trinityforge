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

    /**
     * プレイヤー向け日本語ラベル。この enum が正本で、{@code tools/config-editor/lib/
     * lore-declaration-vocabulary.js} の {@code STACKING_LABELS} はこの switch のミラー
     * ({@link StatTriggerWhen#label()} と同じ規約)。
     */
    public String label() {
        return switch (this) {
            case ADDITIVE -> "加算(複数ソースの値を合計する)";
            case MULTIPLICATIVE -> "乗算(複数ソースの値を掛け合わせる)";
            case MAX_ONLY -> "最大値のみ採用(複数ソースがあっても最大の1つだけ適用される)";
        };
    }
}
