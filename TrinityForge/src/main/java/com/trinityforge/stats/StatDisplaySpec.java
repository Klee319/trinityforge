package com.trinityforge.stats;

import java.util.Objects;

/**
 * Per-stat lore presentation, loaded from {@code stats/lore.yml} (one entry per stat key).
 *
 * @param order     sort key <b>within</b> {@link #category()}; lower appears higher in that section
 * @param category  lore section: attack / defense / craft / gathering / utility / ars / other
 * @param displayScale 内部値→表示値の換算係数({@code stats/lore.yml} の {@code display-scale}、既定 1.0)。
 *                     {@link #unit()} が内部値の単位と一致しないステだけが 1.0 以外を持つ
 *                     (2026-07-31: {@code melee-knockback}/{@code arrow-knockback} は内部値が
 *                     velocity 加算で、単位 {@code m} と桁が合っていなかった)。
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
                              StatCategory category,
                              StatTrigger trigger,
                              StatLimits limits,
                              double displayScale) {

    /** {@code display-scale} 未宣言時の既定(内部値=表示値)。 */
    public static final double DEFAULT_DISPLAY_SCALE = 1.0;

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
        if (!Double.isFinite(displayScale) || displayScale <= 0.0) {
            throw new IllegalArgumentException("display-scale must be finite and > 0: " + displayScale);
        }
        // trigger/limits は任意宣言(段階1/2): 未宣言のstatはnullのまま許可される(許可リストで管理)。
    }

    /** Back-compat without display-scale (換算なし)。 */
    public StatDisplaySpec(String statKey, String displayName, String icon, LoreValueFormat format,
                           int decimals, int order, boolean showSign, boolean hideWhenZero,
                           String unit, StatCategory category, StatTrigger trigger, StatLimits limits) {
        this(statKey, displayName, icon, format, decimals, order, showSign, hideWhenZero,
                unit, category, trigger, limits, DEFAULT_DISPLAY_SCALE);
    }

    /** Back-compat without trigger/limits declaration (未宣言stat)。 */
    public StatDisplaySpec(String statKey, String displayName, String icon, LoreValueFormat format,
                           int decimals, int order, boolean showSign, boolean hideWhenZero,
                           String unit, StatCategory category) {
        this(statKey, displayName, icon, format, decimals, order, showSign, hideWhenZero,
                unit, category, null, null);
    }

    /** Back-compat without unit / category. */
    public StatDisplaySpec(String statKey, String displayName, String icon, LoreValueFormat format,
                           int decimals, int order, boolean showSign, boolean hideWhenZero) {
        this(statKey, displayName, icon, format, decimals, order, showSign, hideWhenZero,
                "", StatCategory.OTHER, null, null);
    }

    /** Back-compat without category. */
    public StatDisplaySpec(String statKey, String displayName, String icon, LoreValueFormat format,
                           int decimals, int order, boolean showSign, boolean hideWhenZero, String unit) {
        this(statKey, displayName, icon, format, decimals, order, showSign, hideWhenZero,
                unit, StatCategory.OTHER, null, null);
    }

    /**
     * 内部値(config/PDC/集計の生値)を表示値へ換算する。<b>表示経路は必ずここを通すこと</b> —
     * lore ({@link #renderValue}) と {@code /tf stats} / {@code /tf status}
     * ({@link StatValueRenderer#render}) で別々に掛けると「チャットとGUIで値が違う」ドリフトになる。
     * 上限値({@code stats/stat-caps.yml})や {@link #limits()} は内部値のままなので換算しない。
     */
    public double toDisplayValue(double internalValue) {
        return internalValue * displayScale;
    }

    /** Renders the numeric part plus optional unit (PERCENT already includes {@code %}). */
    public String renderValue(double value) {
        String text = format.render(toDisplayValue(value), decimals, showSign);
        if (unit.isBlank() || format == LoreValueFormat.PERCENT) {
            return text;
        }
        return text + unit;
    }
}
