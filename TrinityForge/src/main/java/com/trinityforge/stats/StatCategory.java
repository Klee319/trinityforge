package com.trinityforge.stats;

import java.util.Locale;

/**
 * Lore section grouping for item stat display (2026-07-23 stat-gate-overhaul §2.3: 7分類
 * attack/defense/craft/gathering/utility/ars/other)。
 *
 * <p>旧 {@code SUPPORT} 区分は {@link #UTILITY} へ改名統合された。後方互換のため
 * {@link #parse(String)} は {@code "support"} 文字列を引き続き受理し {@link #UTILITY} に解決するが、
 * enum定数としての {@code SUPPORT} 自体はもう存在しない（新規コードは {@code craft}/{@code gathering}
 * を使うこと）。
 */
public enum StatCategory {
    ATTACK,
    DEFENSE,
    CRAFT,
    GATHERING,
    UTILITY,
    ARS,
    OTHER;

    public static StatCategory parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OTHER;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "attack", "攻撃" -> ATTACK;
            case "defense", "防御", "守備" -> DEFENSE;
            case "craft", "クラフト" -> CRAFT;
            case "gathering", "採集" -> GATHERING;
            case "support", "補助", "utility" -> UTILITY; // support = 後方互換エイリアス
            case "ars" -> ARS;
            default -> OTHER;
        };
    }

    /** YAML / editor id for this category. */
    public String configId() {
        return switch (this) {
            case ATTACK -> "attack";
            case DEFENSE -> "defense";
            case CRAFT -> "craft";
            case GATHERING -> "gathering";
            case UTILITY -> "utility";
            case ARS -> "ars";
            case OTHER -> "other";
        };
    }
}
