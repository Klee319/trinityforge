package com.trinityforge.skilltree.effects;

import java.util.Locale;
import java.util.Optional;

/**
 * The runtime surface a dynamic gate id ({@link GateEffectId}, 2026-07-23 動的ID方式改修 §3) compiles onto.
 * Every existing gate/reward surface already used elsewhere in TF/fork stays the single source of truth;
 * this only says which one an id targets.
 *
 * <ul>
 *   <li>{@link #GLYPH_GATE} / {@link #RECIPE_GATE} / {@link #RITUAL_GATE} / {@link #DROP_GATE} — the node's
 *       perk id is unioned into the corresponding {@code target -&gt; perkId(s)} map published by
 *       {@code DedicatedEffectsConfig} ({@code glyphGatePerks()} / {@code recipeGatePerks()} /
 *       {@code ritualGatePerks()} / {@code dropGatePerks()}). Glyph/recipe/ritual are consumed by the fork's
 *       existing {@code usage-gate.yml}/{@code unlock-gate.yml} enforcement; drop is consumed by the
 *       drop-table gimmick listeners (separate implementation wave).</li>
 *   <li>{@link #FLAG} — no existing per-target enforcement surface, or the id already <i>is</i> its own key
 *       (brew/trade/feature/overenchant/reward gate ids, and the bare {@code ars-tier} accumulator).
 *       {@code target} is the capability id itself, published via {@code flagPerks()}.</li>
 * </ul>
 */
public enum DedicatedEffectChannel {
    GLYPH_GATE,
    RECIPE_GATE,
    RITUAL_GATE,
    DROP_GATE,
    FLAG;

    /** Parses the lower-case hyphenated config token (e.g. {@code "glyph-gate"}). Test/tooling convenience. */
    public static Optional<DedicatedEffectChannel> fromConfig(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** Whether this channel is one of the fork/TF gate-derivation channels ({@code *GatePerks()} maps). */
    public boolean isGate() {
        return this == GLYPH_GATE || this == RECIPE_GATE || this == RITUAL_GATE || this == DROP_GATE;
    }
}
