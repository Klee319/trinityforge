package com.trinityforge.skilltree;

/**
 * One {@code dedicated-effects[]} placement on a {@link SkillNode} (2026-07-23 動的ID方式改修 §3): a
 * dynamic gate id (see {@code com.trinityforge.skilltree.effects.GateEffectId}, e.g. {@code glyph:blink} /
 * {@code feature:vein-mining} / {@code ars-tier}) plus the optional numeric {@code value} the id's shape
 * requires (e.g. {@code ars-tier} and {@code feature:dismantle-unlock} always require one; most others
 * never do). {@code value} is {@code null} when the placement carries none.
 */
public record DedicatedEffectEntry(String id, Double value) {
}
