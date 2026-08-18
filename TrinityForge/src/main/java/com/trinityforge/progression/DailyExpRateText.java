package com.trinityforge.progression;

import java.util.Locale;

/**
 * 日次逓減（{@link DailyExpDiminishing}）をプレイヤーへ見せるときの文字列。
 *
 * <p><b>なぜ1本にまとめるか</b>: 同じ状態をボスバー・チャット・スキルツリーGUI・{@code /tf status} の
 * 4か所が出す。整形をそれぞれで書くと「GUIでは 70% なのにチャットでは 69%」のような食い違いが
 * 必ず出る（{@code /tf stats} と {@code /tf status} で実際に踏んだ）。Bukkit に依存させないので
 * サーバ無しでテストできる。
 */
public final class DailyExpRateText {

    private DailyExpRateText() {
    }

    /**
     * 倍率のパーセント表記。{@code 0.7 -> "70%"}、{@code 0.343 -> "34%"}。
     *
     * <p><b>切り捨てではなく四捨五入</b>にしてある。0.343 を「35%」と出すと実際より得に見えるが、
     * 「34%」なら実測(34.3%)と食い違わない範囲に収まる。
     */
    public static String percent(double multiplier) {
        return Math.round(Math.max(0.0, multiplier) * 100.0) + "%";
    }

    /**
     * 残り時間の目安。{@code -1}（該当なし）は {@code null} を返すので、呼び出し側は行ごと落とせる。
     *
     * <p>1時間以上は「約5.4時間」、1時間未満は「約12分」、1分未満は「まもなく」。
     * <b>秒まで出さない</b>のは、この値が指数減衰の見積りで、そもそも1秒の精度を持たないため。
     */
    public static String duration(double millis) {
        if (!Double.isFinite(millis) || millis < 0.0) {
            return null;
        }
        double minutes = millis / 60_000.0;
        if (minutes < 1.0) {
            return "まもなく";
        }
        if (minutes < 60.0) {
            return "約" + Math.round(minutes) + "分";
        }
        return String.format(Locale.ROOT, "約%.1f時間", minutes / 60.0);
    }

    /** EXP量の3桁区切り。{@code 42300 -> "42,300"}。 */
    public static String exp(double amount) {
        return String.format(Locale.ROOT, "%,d", Math.round(Math.max(0.0, amount)));
    }

    /**
     * ボスバー／アクションバーの末尾に足す短い表記。等倍なら {@code null}（＝何も足さない）。
     * 例: {@code " ×70%"}。
     */
    public static String badge(DailyExpDiminishing.Status status) {
        if (status == null || status.atFullRate()) {
            return null;
        }
        return "×" + percent(status.multiplier());
    }
}
