package com.trinityforge.config;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the schema resolver's robustness fixes from cross-review: NaN/Infinity rejection,
 * integer-truncation detection, and range fallback. Uses YamlConfiguration (no Bukkit server).
 */
class SchemaValidationTest {

    private YamlConfiguration yaml(String content) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return cfg;
    }

    @Test
    void nanFallsBackToDefaultAndReportsError() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("v", SchemaField.Type.DOUBLE, 1.0, 0.0, 100.0));
        ValidationResult result = new ValidationResult();
        var values = schema.resolve(yaml("v: .nan"), result);
        assertTrue(result.hasErrors(), "NaN should be flagged");
        assertEquals(1.0, (double) values.get("v"), 0.0);
    }

    @Test
    void infinityFallsBackToDefault() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("v", SchemaField.Type.DOUBLE, 2.0, 0.0, 100.0));
        ValidationResult result = new ValidationResult();
        var values = schema.resolve(yaml("v: .inf"), result);
        assertTrue(result.hasErrors());
        assertEquals(2.0, (double) values.get("v"), 0.0);
    }

    @Test
    void fractionalIntegerIsRejected() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("n", SchemaField.Type.INT, 5, 0, 100));
        ValidationResult result = new ValidationResult();
        var values = schema.resolve(yaml("n: 4.9"), result);
        assertTrue(result.hasErrors(), "fractional value for INT field should be flagged");
        assertEquals(5, (int) values.get("n"));
    }

    @Test
    void outOfRangeFallsBackToDefault() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("v", SchemaField.Type.DOUBLE, 1.0, 0.0, 1.0));
        ValidationResult result = new ValidationResult();
        var values = schema.resolve(yaml("v: 5.0"), result);
        assertTrue(result.hasErrors());
        assertEquals(1.0, (double) values.get("v"), 0.0);
    }

    @Test
    void validValuePassesWithoutError() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("v", SchemaField.Type.DOUBLE, 1.0, 0.0, 100.0));
        ValidationResult result = new ValidationResult();
        var values = schema.resolve(yaml("v: 42.0"), result);
        assertTrue(!result.hasErrors());
        assertEquals(42.0, (double) values.get("v"), 0.0);
    }
}
