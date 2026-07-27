package com.trinityforge.stats;

import java.util.Locale;

/**
 * How a raw stat value is rendered as text in item lore (SELECTION_SPEC 5, COMBAT_SYSTEM_SPEC 5).
 * Pure formatting only: no color or icon (those come from {@link LoreLayout}). The variants mirror
 * the shapes ValhallaMMO uses for its own item stats so addon items read consistently next to
 * ValhallaMMO gear: flat additive numbers, a fraction shown as a percent, plain integers, and a
 * multiplicative scalar.
 */
public enum LoreValueFormat {

    /** Additive number, e.g. {@code +4.5}. */
    FLAT,
    /** Fraction shown as a percentage, e.g. {@code 0.15 -> +15%}. */
    PERCENT,
    /** Rounded to a whole number, e.g. {@code +5}. */
    INTEGER,
    /** Multiplicative scalar, e.g. {@code x1.50}; sign is never forced. */
    SCALAR;

    /**
     * Renders {@code value} as display text. Pure and {@link Locale#ROOT}-based so tests and
     * servers agree regardless of system locale.
     *
     * @param decimals fractional digits (>= 0); ignored by {@link #INTEGER}
     * @param showSign when true, non-negative values get a leading {@code +} (ignored by SCALAR)
     */
    public String render(double value, int decimals, boolean showSign) {
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must be >= 0: " + decimals);
        }
        return switch (this) {
            case FLAT -> sign(value, showSign) + decimal(Math.abs(value), decimals);
            case PERCENT -> sign(value, showSign) + decimal(Math.abs(value) * 100.0, decimals) + "%";
            case INTEGER -> sign(value, showSign) + Long.toString(Math.round(Math.abs(value)));
            case SCALAR -> "x" + decimal(value, decimals);
        };
    }

    /**
     * True when {@code value} rounds to zero at this format's own display precision/scale, so
     * hide-when-zero can key off what would actually be shown rather than the raw value. A raw
     * value like {@code 0.0001} still renders as {@code "+0%"} under {@code PERCENT} with
     * {@code decimals=0} even though it fails a small fixed epsilon check on the raw value; this
     * mirrors each variant's own rounding step ({@link #render}) so the two agree exactly.
     *
     * @param decimals fractional digits (>= 0); ignored by {@link #INTEGER}
     */
    public boolean roundsToZero(double value, int decimals) {
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must be >= 0: " + decimals);
        }
        return switch (this) {
            case FLAT, SCALAR -> roundedMagnitude(Math.abs(value), decimals) == 0.0;
            case PERCENT -> roundedMagnitude(Math.abs(value) * 100.0, decimals) == 0.0;
            case INTEGER -> Math.round(Math.abs(value)) == 0L;
        };
    }

    private static double roundedMagnitude(double magnitude, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(magnitude * scale) / scale;
    }

    private static String sign(double value, boolean showSign) {
        if (value < 0) {
            return "-";
        }
        return showSign ? "+" : "";
    }

    private static String decimal(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
