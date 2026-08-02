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
     * PERCENT の表示桁数の上限(2026-08-02、実サーバ報告「物理耐性5.1935等→せめて5.2%にすべき」対応)。
     * %系ステは視認性が優先で、小数2桁目以降は厳選/品質補正由来の内部端数がそのまま漏れ出すだけで
     * プレイヤーの判断材料にならない。現行 {@code stats/lore.yml} の PERCENT エントリは全て
     * decimals 0〜1 で運用されているため、この上限を掛けても既存表示は一切変わらない —
     * 将来 config に decimals を大きく設定してしまっても(あるいは decimals 未設定時の生値表示など
     * どんな理由であっても)表示だけはここで必ず1桁に丸められる、という防御的な下限線。
     */
    private static final int PERCENT_DECIMALS_CAP = 1;

    /**
     * FLAT/SCALAR の表示桁数の上限。実数系(守備力・体力等)は%系よりわずかに広い精度が意味を持ちうる
     * ため2桁までは許容するが、無制限にはしない(内部値は丸めず、表示だけをここで抑える)。
     */
    private static final int FLAT_DECIMALS_CAP = 2;

    /**
     * Renders {@code value} as display text. Pure and {@link Locale#ROOT}-based so tests and
     * servers agree regardless of system locale.
     *
     * @param decimals fractional digits (>= 0); ignored by {@link #INTEGER}. Clamped internally to
     *                  {@link #PERCENT_DECIMALS_CAP}/{@link #FLAT_DECIMALS_CAP} per format — see
     *                  {@link #cappedDecimals}.
     * @param showSign when true, non-negative values get a leading {@code +} (ignored by SCALAR)
     */
    public String render(double value, int decimals, boolean showSign) {
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must be >= 0: " + decimals);
        }
        int capped = cappedDecimals(decimals);
        return switch (this) {
            case FLAT -> sign(value, showSign) + decimal(Math.abs(value), capped);
            case PERCENT -> sign(value, showSign) + decimal(Math.abs(value) * 100.0, capped) + "%";
            case INTEGER -> sign(value, showSign) + Long.toString(Math.round(Math.abs(value)));
            case SCALAR -> "x" + decimal(value, capped);
        };
    }

    /**
     * キーの種類(このフォーマット)ごとに妥当な表示桁数へ丸める。内部値そのものは一切変えない。
     * パッケージ内可視(private でない): {@link StatValueRenderer} がチャット/GUI側でも同じ上限を
     * 使うために呼ぶ — 2箇所で桁数の規約を別々に持つと「lore と /tf stats で桁数が食い違う」を
     * 再発させるため、必ずこの1箇所を共有すること。
     */
    int cappedDecimals(int decimals) {
        return switch (this) {
            case PERCENT -> Math.min(decimals, PERCENT_DECIMALS_CAP);
            case FLAT, SCALAR -> Math.min(decimals, FLAT_DECIMALS_CAP);
            case INTEGER -> decimals;
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
        int capped = cappedDecimals(decimals);
        return switch (this) {
            case FLAT, SCALAR -> roundedMagnitude(Math.abs(value), capped) == 0.0;
            case PERCENT -> roundedMagnitude(Math.abs(value) * 100.0, capped) == 0.0;
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
