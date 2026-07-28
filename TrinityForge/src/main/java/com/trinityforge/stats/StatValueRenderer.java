package com.trinityforge.stats;

import java.util.Locale;

/**
 * ステータス値の表示文字列化 (2026-07-29 に {@code StatsCommand} から抽出)。
 *
 * <p>{@code /tf stats} のチャット出力と {@code /tf status} のGUIで<b>同じ数値が出る</b>ことを
 * 保証するために1か所へ集約した。以前は StatsCommand の private static だったため、GUI側で
 * 同じ整形を書き直すと「チャットとGUIで値が違う」ドリフトが必ず起きる。
 *
 * <p>丸めの規約: 小数第3位以下は<b>切り捨て</b>(四捨五入ではない)。負値は絶対値側で切り捨てる
 * ので、表示値が実効値より有利側へ振れることはない。
 */
public final class StatValueRenderer {

    private StatValueRenderer() {
    }

    /** lore の format/unit/decimals に従って描画する。PERCENT は ×100 して {@code %} を付ける。 */
    public static String render(StatDisplaySpec spec, double value) {
        int decimals = Math.min(2, spec.decimals());
        LoreValueFormat format = spec.format();
        String text = switch (format) {
            case PERCENT -> {
                double shown = truncate(Math.abs(value) * 100.0, decimals);
                yield sign(value, spec.showSign()) + format(shown, decimals) + "%";
            }
            case INTEGER -> sign(value, spec.showSign())
                    + Long.toString((long) truncate(Math.abs(value), 0));
            case SCALAR -> "x" + format(truncate(value, decimals), decimals);
            case FLAT -> sign(value, spec.showSign())
                    + format(truncate(Math.abs(value), decimals), decimals);
        };
        if (!spec.unit().isBlank() && format != LoreValueFormat.PERCENT) {
            return text + spec.unit();
        }
        return text;
    }

    /** lore 宣言の無いキー用。小数第3位以下切り捨ての素の数値。 */
    public static String plain(double value) {
        return format(truncate(value, 2), 2);
    }

    private static String sign(double value, boolean showSign) {
        if (value < 0) {
            return "-";
        }
        return showSign ? "+" : "";
    }

    /** 小数第 {@code decimals} 位より下を切り捨て(正は floor、負は絶対値側で切り捨て)。 */
    public static double truncate(double value, int decimals) {
        double scale = Math.pow(10, Math.max(0, decimals));
        return Math.floor(Math.abs(value) * scale) / scale * Math.signum(value == 0 ? 1 : value);
    }

    private static String format(double value, int decimals) {
        if (decimals <= 0 || value == Math.rint(value)) {
            return Integer.toString((int) value);
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }
}
