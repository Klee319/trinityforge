package com.trinityforge.mobs;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobTypeDefinitionTest {

    private static MobTypeDefinition def(EntityType type, int level, double coord) {
        return new MobTypeDefinition(type, level, coord, null,
                DefenseStats.NONE, DefenseStats.NONE, AttackStats.plain(0),
                MobLevelCoefficients.ZERO, List.of());
    }

    @Test
    void nonFiniteCoefficientClampsToZero() {
        assertEquals(0.0, def(EntityType.ZOMBIE, 1, Double.NaN).coordinateCoefficient());
        assertEquals(0.0, def(EntityType.ZOMBIE, 1, Double.POSITIVE_INFINITY).coordinateCoefficient());
    }

    @Test
    void negativeLevelThrows() {
        assertThrows(IllegalArgumentException.class, () -> def(EntityType.ZOMBIE, -1, 0.0));
    }

    @Test
    void nonPositiveMaxHealthThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MobTypeDefinition(
                EntityType.ZOMBIE, 1, 0.0, 0.0,
                DefenseStats.NONE, DefenseStats.NONE, AttackStats.plain(0),
                MobLevelCoefficients.ZERO, List.of()));
    }

    @Test
    void dropsListIsDefensivelyCopiedAndUnmodifiable() {
        java.util.ArrayList<MobDropEntry> mutable = new java.util.ArrayList<>();
        mutable.add(new MobDropEntry(org.bukkit.Material.ROTTEN_FLESH, 0.1, 1, 1, null));
        MobTypeDefinition d = new MobTypeDefinition(EntityType.ZOMBIE, 1, 0.0, null,
                DefenseStats.NONE, DefenseStats.NONE, AttackStats.plain(0),
                MobLevelCoefficients.ZERO, mutable);
        mutable.clear();
        assertEquals(1, d.drops().size());
        assertThrows(UnsupportedOperationException.class, () -> d.drops().add(
                new MobDropEntry(org.bukkit.Material.ROTTEN_FLESH, 0.1, 1, 1, null)));
    }

    @Test
    void nullEntityTypeThrows() {
        assertThrows(NullPointerException.class, () -> def(null, 1, 0.0));
    }

    @Test
    void validDefinitionKeepsPositiveFiniteCoefficient() {
        MobTypeDefinition d = def(EntityType.ZOMBIE, 1, 2.5);
        assertTrue(d.coordinateCoefficient() == 2.5);
        assertNull(d.maxHealth());
        assertEquals(0.0, d.levelCoefficients().maxHealth());
    }
}
