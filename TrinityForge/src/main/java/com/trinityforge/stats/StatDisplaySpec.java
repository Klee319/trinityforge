package com.trinityforge.stats;

import java.util.Objects;

/**
 * Per-stat lore presentation, loaded from {@code stats/lore.yml} (one entry per stat key).
 *
 * @param order     sort key <b>within</b> {@link #category()}; lower appears higher in that section
 * @param category  lore section: attack / defense / craft / gathering / utility / ars / other
 */
public record StatDisplaySpec(String statKey,
                              String displayName,
                              String icon,
                              LoreValueFormat format,
                              int decimals,
                              int order,
                              boolean showSign,
                              boolean hideWhenZero,
                              String unit,
                              StatCategory category) {

    public StatDisplaySpec {
        Objects.requireNonNull(statKey, "statKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(category, "category");
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must be >= 0: " + decimals);
        }
    }

    /** Back-compat without unit / category. */
    public StatDisplaySpec(String statKey, String displayName, String icon, LoreValueFormat format,
                           int decimals, int order, boolean showSign, boolean hideWhenZero) {
        this(statKey, displayName, icon, format, decimals, order, showSign, hideWhenZero,
                "", StatCategory.OTHER);
    }

    /** Back-compat without category. */
    public StatDisplaySpec(String statKey, String displayName, String icon, LoreValueFormat format,
                           int decimals, int order, boolean showSign, boolean hideWhenZero, String unit) {
        this(statKey, displayName, icon, format, decimals, order, showSign, hideWhenZero,
                unit, StatCategory.OTHER);
    }

    /** Renders the numeric part plus optional unit (PERCENT already includes {@code %}). */
    public String renderValue(double value) {
        String text = format.render(value, decimals, showSign);
        if (unit.isBlank() || format == LoreValueFormat.PERCENT) {
            return text;
        }
        return text + unit;
    }
}
