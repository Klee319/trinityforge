package com.trinityforge.config;

/**
 * One typed, validated field in a {@link ConfigSchema}.
 * Numeric fields may carry an inclusive [min, max] range; non-numeric fields use {@link #of}.
 */
public final class SchemaField {

    public enum Type { DOUBLE, INT, BOOLEAN, STRING, STRING_LIST }

    private final String path;
    private final Type type;
    private final Object defaultValue;
    private final Double min;
    private final Double max;
    private final boolean exclusiveMin;

    private SchemaField(String path, Type type, Object defaultValue, Double min, Double max,
                        boolean exclusiveMin) {
        this.path = path;
        this.type = type;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.exclusiveMin = exclusiveMin;
    }

    /** Numeric field with an inclusive range {@code [min, max]}. */
    public static SchemaField number(String path, Type type, Object defaultValue, double min, double max) {
        return numeric(path, type, defaultValue, min, max, false);
    }

    /**
     * Numeric field whose lower bound is <b>exclusive</b> ({@code (min, max]}), rejecting {@code min}
     * itself. Used where {@code min} would be a degenerate value (e.g. a divisor that must be {@code > 0}
     * to avoid a division by zero); an out-of-range value falls back to the default like any other.
     */
    public static SchemaField numberExclusiveMin(String path, Type type, Object defaultValue,
                                                 double min, double max) {
        return numeric(path, type, defaultValue, min, max, true);
    }

    private static SchemaField numeric(String path, Type type, Object defaultValue,
                                       double min, double max, boolean exclusiveMin) {
        if (min > max) {
            throw new IllegalArgumentException(
                    "field '" + path + "' has inverted range: min (" + min + ") > max (" + max + ")");
        }
        return new SchemaField(path, type, defaultValue, min, max, exclusiveMin);
    }

    /** Field without range constraints (booleans, strings, lists, or unbounded numbers). */
    public static SchemaField of(String path, Type type, Object defaultValue) {
        return new SchemaField(path, type, defaultValue, null, null, false);
    }

    public String path() { return path; }
    public Type type() { return type; }
    public Object defaultValue() { return defaultValue; }
    public Double min() { return min; }
    public Double max() { return max; }

    /** Whether {@link #min()} is an exclusive lower bound (value must be strictly greater than min). */
    public boolean exclusiveMin() { return exclusiveMin; }
}
