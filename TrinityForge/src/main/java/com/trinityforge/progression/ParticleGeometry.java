package com.trinityforge.progression;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for the {@code circle} particle shape (Bukkit-free, unit-testable). The Bukkit-
 * facing driver is {@link ParticleEffectService}. {@code aura} shape needs no geometry (a single
 * random-offset burst at the entity origin), so only {@code circle} has a pure helper here.
 */
public final class ParticleGeometry {

    private ParticleGeometry() {
    }

    /**
     * {@code count} evenly spaced {@code [dx, dz]} offsets on a circle of {@code radius} (y=0, caller
     * adds height). {@code count <= 0} or {@code radius <= 0} yields an empty list rather than
     * throwing (fail-safe for a misconfigured particle def).
     */
    public static List<double[]> circleOffsets(int count, double radius) {
        List<double[]> points = new ArrayList<>();
        if (count <= 0 || radius <= 0) {
            return points;
        }
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI * i) / count;
            points.add(new double[] {radius * Math.cos(angle), radius * Math.sin(angle)});
        }
        return points;
    }
}
