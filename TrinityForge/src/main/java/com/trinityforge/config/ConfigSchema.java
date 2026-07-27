package com.trinityforge.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ordered set of {@link SchemaField} definitions for one config domain.
 * {@link #resolve} validates a loaded section and produces a fully-populated value map,
 * falling back to the declared default for every missing or invalid field (errors collected,
 * never thrown) so the plugin always starts with a complete, sane configuration.
 */
public final class ConfigSchema {

    private final List<SchemaField> fields = new ArrayList<>();

    public ConfigSchema field(SchemaField field) {
        fields.add(field);
        return this;
    }

    public List<SchemaField> fields() {
        return Collections.unmodifiableList(fields);
    }

    public Map<String, Object> resolve(ConfigurationSection section, ValidationResult result) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (SchemaField field : fields) {
            values.put(field.path(), resolveField(section, field, result));
        }
        return values;
    }

    private Object resolveField(ConfigurationSection section, SchemaField field, ValidationResult result) {
        if (section == null || !section.contains(field.path())) {
            return field.defaultValue();
        }
        Object raw = section.get(field.path());
        return switch (field.type()) {
            case DOUBLE -> resolveNumber(field, raw, result, false);
            case INT -> resolveNumber(field, raw, result, true);
            case BOOLEAN -> raw instanceof Boolean b ? b : typeError(field, raw, "boolean", result);
            case STRING -> raw instanceof String s ? s : typeError(field, raw, "string", result);
            case STRING_LIST -> raw instanceof List<?> list ? list : typeError(field, raw, "list", result);
        };
    }

    private Object resolveNumber(SchemaField field, Object raw, ValidationResult result, boolean asInt) {
        if (!(raw instanceof Number number)) {
            return typeError(field, raw, asInt ? "integer" : "number", result);
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value)) {
            result.addError("'" + field.path() + "' value " + value
                    + " is not finite -> using default " + field.defaultValue());
            return field.defaultValue();
        }
        if (asInt && value != Math.floor(value)) {
            result.addError("'" + field.path() + "' expected integer but got fractional " + value
                    + " -> using default " + field.defaultValue());
            return field.defaultValue();
        }
        boolean belowMin = field.min() != null
                && (field.exclusiveMin() ? value <= field.min() : value < field.min());
        boolean aboveMax = field.max() != null && value > field.max();
        if (belowMin || aboveMax) {
            String lowerBracket = field.exclusiveMin() ? "(" : "[";
            result.addError("'" + field.path() + "' value " + value + " out of range " + lowerBracket
                    + field.min() + ", " + field.max() + "] -> using default " + field.defaultValue());
            return field.defaultValue();
        }
        return asInt ? number.intValue() : value;
    }

    private Object typeError(SchemaField field, Object raw, String expected, ValidationResult result) {
        String actual = raw == null ? "null" : raw.getClass().getSimpleName();
        result.addError("'" + field.path() + "' expected " + expected + " but got " + actual
                + " -> using default " + field.defaultValue());
        return field.defaultValue();
    }
}
