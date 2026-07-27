package com.trinityforge.combat;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides whether a crit lands for a given chance. Pulled out as an interface so the
 * pipeline stays deterministic under test (inject a fixed resolver) while production
 * uses a real RNG.
 */
@FunctionalInterface
public interface CritResolver {

    boolean rolls(double chance);

    /** Production RNG-backed resolver. */
    CritResolver RANDOM = chance -> chance > 0 && ThreadLocalRandom.current().nextDouble() < chance;

    /** Always-crit resolver (tests / deterministic max-roll). */
    CritResolver ALWAYS = chance -> chance > 0;

    /** Never-crit resolver (tests / deterministic min-roll). */
    CritResolver NEVER = chance -> false;
}
