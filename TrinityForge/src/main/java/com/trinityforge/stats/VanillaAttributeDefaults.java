package com.trinityforge.stats;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Vanilla {@code Attribute} default values for the {@link StatVocabulary.Channel#ATTRIBUTE} channel
 * (2026-07-25 config editor T1 "absolute value" base-stats rework). Single source of truth for the
 * numbers so they are not hand-copied into every call site; values are taken from the Minecraft Wiki
 * "Attribute" page (also cited in {@code combat/base-stats.yml}'s header comment).
 *
 * <p>Which canonical keys belong to the ATTRIBUTE channel is already owned by {@link StatVocabulary}
 * ({@code ATTRIBUTE_KEYS}) — this class deliberately does not redeclare that set. Instead, a static
 * consistency check at class-load time asserts that this table's key set matches
 * {@code StatVocabulary}'s ATTRIBUTE channel exactly, so the two can never silently drift apart: adding
 * an ATTRIBUTE-channel key to {@link StatVocabulary} without adding its vanilla default here (or vice
 * versa) fails fast with an {@link ExceptionInInitializerError} instead of quietly misbehaving at
 * runtime (e.g. treating a written absolute value as if the vanilla default were 0).
 */
public final class VanillaAttributeDefaults {

    private static final Map<String, Double> DEFAULTS = Map.of(
            // 2026-07-26 stat-scope 境界引き直し §2 (C→A 降格): attack-speed(絶対値)は
            // StatVocabulary の ATTRIBUTE チャネルから外れた(アイテム固有ステとなり、
            // PerkAttributeApplier が DerivedItemStats.resolve で直接読むためこの表を経由しない)。
            // 2026-07-25: attack-speed-bonus は割合ボーナス(0=無し)なので「バニラ既定値」は0.0。
            // base-stats.yml に書いた値がそのまま加算量になる(絶対値変換しても0引くだけなので実質恒等)。
            StatKeys.canonical("attack-speed-bonus"), 0.0,
            StatKeys.canonical("attack-reach"), 3.0,
            StatKeys.canonical("max-health"), 20.0,
            StatKeys.canonical("move-speed"), 0.1,
            StatKeys.canonical("knockback-resistance"), 0.0);

    static {
        Set<String> attributeChannelKeys = StatVocabulary.allKeys().stream()
                .filter(StatVocabulary::isAttribute)
                .collect(Collectors.toUnmodifiableSet());
        if (!DEFAULTS.keySet().equals(attributeChannelKeys)) {
            throw new IllegalStateException(
                    "VanillaAttributeDefaults drifted from StatVocabulary's ATTRIBUTE channel: "
                            + "defaults=" + DEFAULTS.keySet() + " vocabulary=" + attributeChannelKeys);
        }
    }

    private VanillaAttributeDefaults() {
    }

    /**
     * The vanilla default for a (kebab- or snake-case) ATTRIBUTE-channel stat key, or {@code 0.0} for
     * any key outside the ATTRIBUTE channel (safe no-op default for callers that don't pre-filter).
     */
    public static double get(String statKey) {
        return DEFAULTS.getOrDefault(StatKeys.canonical(statKey), 0.0);
    }
}
