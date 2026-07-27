package com.trinityforge.skilltree.effects;

import java.util.Locale;
import java.util.Optional;

/**
 * Parses a node's {@code dedicated-effects[].id} placement string (2026-07-23 動的ID方式改修 §3.1) into
 * the {@link DedicatedEffectChannel} it compiles onto plus the channel-specific {@code target} key, purely
 * from the id's own shape — no catalog file is consulted (that static catalog is gone; see the design doc
 * for why).
 *
 * <p>Recognized prefixes (case-insensitive on the prefix only): {@code glyph:} / {@code recipe:} /
 * {@code ritual:} / {@code drop:} route to their matching gate channel with {@code target} = the text after
 * the prefix (e.g. {@code glyph:blink} -&gt; target {@code blink}; {@code drop:mining:tier1} -&gt; target
 * {@code mining:tier1}, i.e. the {@code drop:} prefix is stripped once, the remaining colons are part of the
 * target). {@code brew:} / {@code trade:} / {@code feature:} / {@code overenchant:} / {@code reward:} route
 * to {@link DedicatedEffectChannel#FLAG} with {@code target} = the <b>full original id</b> (prefix
 * included) — these have no existing per-target bucket to key into, so the flag id doubles as its own key
 * (matches the pre-existing {@code flagPerks()} convention of "capabilityId = effect id itself"). The bare
 * literal {@code ars-tier} (no colon, no other text) is also a {@link DedicatedEffectChannel#FLAG} whose
 * target is itself.
 *
 * <p>Anything else (blank, no colon and not exactly {@code ars-tier}, unrecognized prefix, or a prefix with
 * an empty remainder) fails to parse ({@link Optional#empty()}) — the caller ({@code SkillTreeConfig}) is
 * responsible for treating that as a warn+skip (either a config typo or a not-yet-converted legacy id from
 * before this wave; see the design doc §5 W2c note).
 */
public record GateEffectId(DedicatedEffectChannel channel, String target) {

    /** Bare literal id (no prefix) for the ArsTier accumulator effect. */
    public static final String ARS_TIER = "ars-tier";

    public static Optional<GateEffectId> parse(String rawId) {
        if (rawId == null) {
            return Optional.empty();
        }
        String id = rawId.trim();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        if (id.equals(ARS_TIER)) {
            return Optional.of(new GateEffectId(DedicatedEffectChannel.FLAG, ARS_TIER));
        }
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return Optional.empty(); // no prefix, or "prefix:" with nothing after it
        }
        String prefix = id.substring(0, colon).toLowerCase(Locale.ROOT);
        String rest = id.substring(colon + 1).trim();
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        return switch (prefix) {
            case "glyph" -> Optional.of(new GateEffectId(DedicatedEffectChannel.GLYPH_GATE, rest));
            case "recipe" -> Optional.of(new GateEffectId(DedicatedEffectChannel.RECIPE_GATE, rest));
            case "ritual" -> Optional.of(new GateEffectId(DedicatedEffectChannel.RITUAL_GATE, rest));
            case "drop" -> Optional.of(new GateEffectId(DedicatedEffectChannel.DROP_GATE, rest));
            case "brew", "trade", "feature", "overenchant", "reward" ->
                    Optional.of(new GateEffectId(DedicatedEffectChannel.FLAG, id));
            default -> Optional.empty();
        };
    }

    /** {@code feature:<id>} 専用の便利ヘルパー: このidが feature プレフィックスなら中身のfeature idを返す。 */
    public static Optional<String> featureIdOf(String rawId) {
        if (rawId == null) {
            return Optional.empty();
        }
        String id = rawId.trim();
        String prefix = "feature:";
        if (!id.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            return Optional.empty();
        }
        String rest = id.substring(prefix.length()).trim();
        return rest.isEmpty() ? Optional.empty() : Optional.of(rest);
    }
}
