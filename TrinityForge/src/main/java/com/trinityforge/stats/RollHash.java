package com.trinityforge.stats;

import java.util.SplittableRandom;

/**
 * Deterministic standard-normal draw for the per-item random stat roll (ITEM_ECONOMY_SPEC 5.1 roll layer).
 * A stat rolled from an item's {@code rollSeed} must be reproducible: the same item (same rollSeed) + the
 * same stat key must always yield the same value, so the roll can be re-derived live at every combat/lore
 * derivation without baking it into the item. Bukkit-free and pure so it is unit-testable.
 *
 * <p>The seed and the stat key are mixed through the SplitMix64 finalizer into a well-distributed per-stat
 * seed, which seeds a {@link SplittableRandom} whose {@code nextGaussian()} yields the N(0,1) sample fed to
 * {@link QualityRollModel#reach} (the split-normal roll draw). This mirrors how the EliteMobs drop path
 * derives its seeded Gaussian ({@code new SplittableRandom(rollSeed).nextGaussian()}), so both the quality
 * LEVEL and the per-stat roll use the same reproducible-Gaussian style. Distinct stat keys on the same item
 * roll independently (each stat gets its own seed), and distinct items (rollSeeds) roll independently.
 */
public final class RollHash {

    private RollHash() {
    }

    /**
     * A well-distributed per-stat seed from an item {@code rollSeed} and a canonical stat key (SplitMix64
     * finalizer). Distinct keys / seeds map to independent seeds.
     */
    private static long seedFor(long rollSeed, String statKey) {
        long h = rollSeed * 0x9E3779B97F4A7C15L
                + (statKey == null ? 0L : statKey.hashCode()) * 0xC2B2AE3D27D4EB4FL
                + 0x165667B19E3779F9L;
        // SplitMix64 finalizer for good avalanche.
        h ^= (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return h;
    }

    /**
     * A deterministic sample from the standard normal N(0,1) for an item {@code rollSeed} and a canonical
     * stat key. Fed to {@link QualityRollModel#reach} as the split-normal draw (Z ≥ 0 uses the up spread,
     * Z &lt; 0 the down spread). Reproducible: same {@code rollSeed} + key always yields the same Z.
     */
    public static double standardNormal(long rollSeed, String statKey) {
        return new SplittableRandom(seedFor(rollSeed, statKey)).nextGaussian();
    }

    /**
     * Deterministic unit interval draw in {@code [0, 1)} for grant-chance gating
     * ({@code advanced.randomize-grants}). Same {@code rollSeed} + key always yields the same value.
     */
    public static double unitInterval(long rollSeed, String key) {
        return new SplittableRandom(seedFor(rollSeed, key)).nextDouble();
    }
}
