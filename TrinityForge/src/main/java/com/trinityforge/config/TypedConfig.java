package com.trinityforge.config;

import java.util.List;
import java.util.Map;

/**
 * Immutable, fully-resolved view of one config domain. Every key present here was either
 * read-and-validated or filled from its schema default, so accessors never return null
 * for a declared field.
 */
public final class TypedConfig {

    private final Map<String, Object> values;

    public TypedConfig(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    public double getDouble(String path) {
        return ((Number) require(path)).doubleValue();
    }

    public int getInt(String path) {
        return ((Number) require(path)).intValue();
    }

    public boolean getBoolean(String path) {
        return (Boolean) require(path);
    }

    public String getString(String path) {
        return (String) require(path);
    }

    public List<String> getStringList(String path) {
        List<?> raw = (List<?>) require(path);
        return raw.stream().map(String::valueOf).toList();
    }

    private Object require(String path) {
        Object value = values.get(path);
        if (value == null) {
            throw new IllegalArgumentException("Unknown config field '" + path + "' (not declared in schema)");
        }
        return value;
    }
}
