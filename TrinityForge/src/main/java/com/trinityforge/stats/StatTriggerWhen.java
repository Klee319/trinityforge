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

    /**
     * プレイヤー向け日本語ラベル。この enum が正本で、{@code tools/config-editor/lib/
     * lore-declaration-vocabulary.js} の {@code TRIGGER_WHEN_LABELS} はこの switch のミラー。
     * {@code lore-declaration-vocabulary-java-parity.test.js} が両者の一致を検証する
     * (K-5段階3: {@code /tf stats detail} 用。二重管理を避けるため、この enum を唯一の正本とする)。
     */
    public String label() {
        return switch (this) {
            case ON_MELEE_HIT -> "近接攻撃時";
            case ON_PROJECTILE_HIT -> "飛び道具命中時";
            case ON_ANY_HIT -> "攻撃命中時(近接/飛び道具問わず)";
            case ON_DAMAGE_TAKEN -> "被弾時";
            case ON_KILL -> "撃破時";
            case PASSIVE_ATTRIBUTE -> "常時(バニラ属性へ直接反映)";
            case PASSIVE -> "常時(独自ロジックで常時適用)";
            case ON_BLOCK_BREAK -> "ブロック破壊時";
            case ON_CRAFT -> "クラフト時";
            case ON_BREW -> "醸造時";
            case ON_ENCHANT -> "エンチャント時";
            case ON_FISH -> "釣り時";
            case ON_SMELT -> "精錬時";
            case ON_DISASSEMBLE -> "解体時";
            case ON_CONSUME -> "飲食時";
            case ON_SPELL_CAST -> "魔法詠唱時";
        };
    }
}
