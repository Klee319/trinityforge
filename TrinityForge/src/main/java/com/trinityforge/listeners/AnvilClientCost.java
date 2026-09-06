package com.trinityforge.listeners;

/**
 * 金床コストのクライアント表示。
 *
 * <p>Java 版クライアント({@code AnvilScreen})はサーバの {@code maximumRepairCost} を見ない。
 * 送られてきたコストが {@link #CLIENT_TOO_EXPENSIVE_AT} 以上なら、クリエイティブ以外では
 * 必ず {@code container.repair.expensive}（コストが高すぎます）を出す。バニラ自身も改名専用のとき
 * コストを 39 に落とす同じ逃げを使っている。Prepare の最中に 39 へ落とすと Paper が
 * ハンドラ後に実コストと上限40を再比較して結果を空にするので、表示の 39 は次tickで送る。
 */
final class AnvilClientCost {

    static final int CLIENT_TOO_EXPENSIVE_AT = 40;
    static final int CLIENT_DISPLAY_CAP = CLIENT_TOO_EXPENSIVE_AT - 1;

    private AnvilClientCost() {
    }

    static boolean needsDisplayCap(int realCost) {
        return realCost >= CLIENT_TOO_EXPENSIVE_AT;
    }

    /** クライアントへ送る値。40 以上なら 39。 */
    static int displayed(int realCost) {
        return needsDisplayCap(realCost) ? CLIENT_DISPLAY_CAP : realCost;
    }
}
