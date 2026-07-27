package com.trinityforge.mobs;

/**
 * Pure distance-based level scaling for {@link MobTypeDefinition}: a mob's effective combat level
 * rises by {@code coordinate-coefficient} per block of distance from world spawn. Bukkit-free so
 * it is directly unit-testable.
 */
public final class MobLevelScaling {

    private MobLevelScaling() {
    }

    /**
     * {@code effectiveLevel = base + floor(distance * coefficient)}, clamped to never go below 0.
     * A negative {@code distance} is defensively treated as 0 (Bukkit's {@code Location#distance}
     * never returns negative in practice, but this stays safe regardless of the caller).
     *
     * <p>Uncapped (CMB-21): kept for existing callers/tests that don't have a configured ceiling.
     * New call sites should prefer {@link #effectiveLevel(int, double, double, int)} with a real
     * {@code max-level} so distant mobs cannot scale to an unbounded level (which saturates
     * penetration and makes all defense stats meaningless).
     */
    public static int effectiveLevel(int base, double coefficient, double distance) {
        return effectiveLevel(base, coefficient, distance, Integer.MAX_VALUE);
    }

    /**
     * Same as {@link #effectiveLevel(int, double, double)}, additionally clamped to at most
     * {@code maxLevel} (CMB-21: distance-based scaling used to be unbounded — a mob 50,000 blocks
     * from spawn could reach ~Lv1000, at which point penetration saturates to 1.0 and defense stats
     * become meaningless). {@code maxLevel < 0} is treated as 0 (defensive; config parsing already
     * clamps {@code max-level} to >= 0 before it reaches here).
     */
    public static int effectiveLevel(int base, double coefficient, double distance, int maxLevel) {
        double safeDistance = Math.max(0.0, distance);
        long scaled = (long) Math.floor(safeDistance * coefficient);
        long result = base + scaled;
        long safeMaxLevel = Math.max(0, maxLevel);
        return (int) Math.max(0, Math.min(safeMaxLevel, result));
    }
}
