package com.trinityforge.stats;

/**
 * A single declared numeric bound from {@code stats/lore.yml limits:} (e.g. {@code cap},
 * {@code max-distance}), paired with an optional machine-resolvable pointer to the implementation
 * value it must match ({@code cap-ref}, {@code max-distance-ref}, ...). {@link #ref()} is resolved by
 * {@link CapRefResolver} and asserted equal to {@link #value()} by the constraint test — that is the
 * whole point of this type: a config author can no longer let the declared number and the
 * implementation drift apart unnoticed.
 *
 * @param value the declared literal bound
 * @param ref   machine-resolvable pointer to the real value source (nullable; see {@link CapRefResolver})
 */
public record StatBound(double value, String ref) {
}
