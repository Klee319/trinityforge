package com.trinityforge.stats;

import java.util.Locale;
import java.util.Objects;

/**
 * Closed vocabulary for {@code stats/lore.yml stats.<key>.trigger.when}: declares <b>when</b> a stat
 * actually fires, machine-readably. This is intentionally a fixed enum (Java + JS mirror in
 * {@code tools/config-editor/lib/schema.js}) rather than free text — new members require explicit
 * sign-off, they are not something a config author can silently invent (段階1/2 design decision:
 * "この語彙で表せない実装に出会ったら、勝手にメンバーを追加せず、実装せずに報告して止まること").
 */
public enum StatTriggerWhen {
    ON_MELEE_HIT,
    ON_PROJECTILE_HIT,
    ON_ANY_HIT,
    ON_DAMAGE_TAKEN,
    ON_KILL,
    /** Mirrors onto a vanilla {@code Attribute} modifier and is therefore always-on while equipped. */
    PASSIVE_ATTRIBUTE,
    /** Always-on effect that is NOT a vanilla attribute mirror (custom always-on logic). */
    PASSIVE,
    ON_BLOCK_BREAK,
    ON_CRAFT,
    ON_BREW,
    ON_ENCHANT,
    ON_FISH,
    ON_SMELT,
    ON_DISASSEMBLE,
    /** Eating/drinking. */
    ON_CONSUME,
    ON_SPELL_CAST;

    public static StatTriggerWhen parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown trigger.when '" + raw + "'", ex);
        }
    }
}
