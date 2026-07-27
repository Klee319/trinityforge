package com.trinityforge.progression;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure geometry for the {@code circle} particle shape (2026-07-23-stat-gate-overhaul §6.1/§6.7). */
class ParticleGeometryTest {

    @Test
    void producesRequestedCountAtGivenRadius() {
        List<double[]> points = ParticleGeometry.circleOffsets(4, 2.0);
        assertEquals(4, points.size());
        for (double[] point : points) {
            double dist = Math.sqrt(point[0] * point[0] + point[1] * point[1]);
            assertTrue(Math.abs(dist - 2.0) < 1e-9);
        }
    }

    @Test
    void zeroOrNegativeCountYieldsEmpty() {
        assertTrue(ParticleGeometry.circleOffsets(0, 2.0).isEmpty());
        assertTrue(ParticleGeometry.circleOffsets(-1, 2.0).isEmpty());
    }

    @Test
    void zeroOrNegativeRadiusYieldsEmpty() {
        assertTrue(ParticleGeometry.circleOffsets(4, 0.0).isEmpty());
        assertTrue(ParticleGeometry.circleOffsets(4, -1.0).isEmpty());
    }

    @Test
    void firstPointIsAlongPositiveXAxis() {
        List<double[]> points = ParticleGeometry.circleOffsets(1, 3.0);
        assertEquals(3.0, points.get(0)[0], 1e-9);
        assertEquals(0.0, points.get(0)[1], 1e-9);
    }
}
