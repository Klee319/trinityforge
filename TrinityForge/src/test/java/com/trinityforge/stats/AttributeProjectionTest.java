package com.trinityforge.stats;

import com.trinityforge.stats.AttributeProjection.AttributeMapEntry;
import com.trinityforge.stats.AttributeProjection.AttributeModifierSpec;
import com.trinityforge.stats.AttributeProjection.AttributeOperation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeProjectionTest {

    private static AttributeProjection projectionWith(Map<String, AttributeMapEntry> entries) {
        return new AttributeProjection(entries);
    }

    @Test
    void mapsStatThroughScaleFactor() {
        AttributeProjection projection = projectionWith(Map.of(
                "attack_power", new AttributeMapEntry("GENERIC_ATTACK_DAMAGE", AttributeOperation.ADD_NUMBER, 1.0)));

        List<AttributeModifierSpec> specs = projection.project(Map.of("attack_power", 7.0));

        assertEquals(1, specs.size());
        AttributeModifierSpec spec = specs.get(0);
        assertEquals("attack_power", spec.statKey());
        assertEquals("GENERIC_ATTACK_DAMAGE", spec.attributeKey());
        assertEquals(AttributeOperation.ADD_NUMBER, spec.operation());
        assertEquals(7.0, spec.amount());
    }

    @Test
    void appliesNonUnitScale() {
        AttributeProjection projection = projectionWith(Map.of(
                "move_speed", new AttributeMapEntry("GENERIC_MOVEMENT_SPEED", AttributeOperation.ADD_SCALAR, 0.01)));

        List<AttributeModifierSpec> specs = projection.project(Map.of("move_speed", 5.0));

        assertEquals(1, specs.size());
        assertEquals(0.05, specs.get(0).amount(), 1.0e-9);
    }

    @Test
    void skipsStatsWithoutAMapping() {
        AttributeProjection projection = projectionWith(Map.of(
                "attack_power", new AttributeMapEntry("GENERIC_ATTACK_DAMAGE", AttributeOperation.ADD_NUMBER, 1.0)));

        // penetration% is PDC-only: it has no attribute analogue and must not leak out.
        List<AttributeModifierSpec> specs = projection.project(Map.of("penetration_rate", 30.0));

        assertTrue(specs.isEmpty());
    }

    @Test
    void skipsZeroScaledAmounts() {
        AttributeProjection projection = projectionWith(Map.of(
                "attack_power", new AttributeMapEntry("GENERIC_ATTACK_DAMAGE", AttributeOperation.ADD_NUMBER, 1.0)));

        List<AttributeModifierSpec> specs = projection.project(Map.of("attack_power", 0.0));

        assertTrue(specs.isEmpty(), "a zero amount is a no-op modifier and should be omitted");
    }

    @Test
    void projectsMultipleMappedStats() {
        AttributeProjection projection = projectionWith(Map.of(
                "attack_power", new AttributeMapEntry("GENERIC_ATTACK_DAMAGE", AttributeOperation.ADD_NUMBER, 1.0),
                "max_health", new AttributeMapEntry("GENERIC_MAX_HEALTH", AttributeOperation.ADD_NUMBER, 2.0)));

        List<AttributeModifierSpec> specs =
                projection.project(Map.of("attack_power", 3.0, "max_health", 4.0, "crit_rate", 50.0));

        assertEquals(2, specs.size());
        double total = specs.stream().mapToDouble(AttributeModifierSpec::amount).sum();
        assertEquals(3.0 + 8.0, total, 1.0e-9);
    }

    @Test
    void returnedListIsImmutable() {
        AttributeProjection projection = projectionWith(Map.of(
                "attack_power", new AttributeMapEntry("GENERIC_ATTACK_DAMAGE", AttributeOperation.ADD_NUMBER, 1.0)));

        List<AttributeModifierSpec> specs = projection.project(Map.of("attack_power", 1.0));

        assertThrows(UnsupportedOperationException.class,
                () -> specs.add(new AttributeModifierSpec("x", "X", AttributeOperation.ADD_NUMBER, 1.0)));
    }

    @Test
    void rejectsNonFiniteScale() {
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeMapEntry("GENERIC_ATTACK_DAMAGE", AttributeOperation.ADD_NUMBER, Double.NaN));
    }

    @Test
    void rejectsNonFiniteAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeModifierSpec("attack_power", "GENERIC_ATTACK_DAMAGE",
                        AttributeOperation.ADD_NUMBER, Double.POSITIVE_INFINITY));
    }

    @Test
    void resolvesKebabRolledStatAgainstSnakeMappingEntry() {
        // attribute-map.yml authors snake_case; roll.yml authors kebab-case. Before gap I1 was fixed
        // the lookup missed and no modifier was produced. The entry key and the rolled key differ only
        // in separator convention; they must now resolve to the same canonical stat.
        AttributeProjection projection = projectionWith(Map.of(
                "attack_damage", new AttributeMapEntry("attack_damage", AttributeOperation.ADD_NUMBER, 1.0)));

        List<AttributeModifierSpec> specs = projection.project(Map.of("attack-damage", 7.0));

        assertEquals(1, specs.size());
        AttributeModifierSpec spec = specs.get(0);
        assertEquals("attack-damage", spec.statKey(), "the original rolled key is kept for identity");
        assertEquals("attack_damage", spec.attributeKey());
        assertEquals(7.0, spec.amount());
    }

    @Test
    void roundTripsKebabStatToAttributeModifier() {
        // End-to-end across both key conventions: a kebab-case stat key (as authored in
        // stats/item-stats.yml) is projected through a snake-case mapping entry and lands on the
        // expected vanilla attribute (gap I1 round-trip).
        String kebabKey = "attack-damage";
        double value = 6.5;

        AttributeProjection projection = projectionWith(Map.of(
                "attack_damage", new AttributeMapEntry("attack_damage", AttributeOperation.ADD_NUMBER, 1.0)));

        List<AttributeModifierSpec> specs = projection.project(Map.of(kebabKey, value));

        assertEquals(1, specs.size(), "the kebab stat key must resolve to its snake mapping entry");
        assertEquals("attack_damage", specs.get(0).attributeKey());
        assertEquals(value, specs.get(0).amount(), 1.0e-9);
    }

    @Test
    void skipsNonFiniteStatValues() {
        AttributeProjection projection = projectionWith(Map.of(
                "attack_power", new AttributeMapEntry("GENERIC_ATTACK_DAMAGE", AttributeOperation.ADD_NUMBER, 1.0)));

        // A NaN stat value would slip past the exact-zero check; it must be skipped, not emitted.
        List<AttributeModifierSpec> specs = projection.project(Map.of("attack_power", Double.NaN));

        assertTrue(specs.isEmpty());
    }
}
