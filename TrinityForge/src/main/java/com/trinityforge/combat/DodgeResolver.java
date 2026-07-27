package com.trinityforge.combat;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides whether the defender dodges an incoming attack for a given chance
 * (SKILL_TREE_SPEC 6.2, Q3 = (c) new implementation).
 *
 * <p>Dodge is resolved <strong>once per attack</strong> and, on success, avoids the whole attack
 * (all components collapse to 0, magical included), so it lives at the attack-resolution layer
 * ({@link SymmetricDamagePipeline}) as a binary pre-check ahead of the per-component 8-step math.
 * Keeping the "0 damage on dodge" outcome inside the pipeline preserves TrinityForge as the sole
 * authority on final damage (LD-9). Pulled out as an interface, mirroring {@link CritResolver}, so
 * the pipeline stays deterministic under test (inject a fixed resolver) while production uses RNG.
 */
@FunctionalInterface
public interface DodgeResolver {

    boolean rolls(double chance);

    /** Production RNG-backed resolver. A non-positive chance never dodges. */
    DodgeResolver RANDOM = chance -> chance > 0 && ThreadLocalRandom.current().nextDouble() < chance;

    /** Always-dodge resolver for a positive chance (tests / deterministic max-roll). */
    DodgeResolver ALWAYS = chance -> chance > 0;

    /** Never-dodge resolver (tests / deterministic min-roll). */
    DodgeResolver NEVER = chance -> false;

    /**
     * Wraps {@code delegate} so the incoming chance is clamped to {@code [0, maxChance]} before the
     * roll (B3 sibling for dodge: an uncapped 回避率 could reach 1.0 = permanent, unconditional
     * dodge-immunity, same failure mode as the mitigation-rate cap on {@link DefenseStats}).
     * {@code combat/damage.yml defense.max-dodge-chance} feeds {@code maxChance}; {@code 1.0} disables
     * the cap (legacy behaviour).
     */
    static DodgeResolver capped(DodgeResolver delegate, double maxChance) {
        Objects.requireNonNull(delegate, "delegate");
        double cap = Double.isFinite(maxChance) ? Math.max(0.0, Math.min(1.0, maxChance)) : 1.0;
        return chance -> delegate.rolls(Math.min(chance, cap));
    }
}
