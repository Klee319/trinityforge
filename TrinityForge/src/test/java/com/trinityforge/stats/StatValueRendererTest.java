package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ステータス値の整形 (2026-07-29 に {@code StatsCommand} から抽出)。
 *
 * <p>丸めは<b>切り捨て</b>。四捨五入にすると表示が実効値より有利側へ振れて
 * 「表示では上限に届いているのに効果が出ない」という問い合わせを生む。
 */
class StatValueRendererTest {

    private static StatDisplaySpec spec(LoreValueFormat format, int decimals, boolean showSign, String unit) {
        return new StatDisplaySpec("k", "K", "", format, decimals, 0, showSign, true, unit,
                StatCategory.OTHER, null, null);
    }

    @Test
    void percentMultipliesByHundredAndTruncates() {
        assertEquals("15.6%", StatValueRenderer.render(spec(LoreValueFormat.PERCENT, 1, false, ""), 0.1567));
        assertEquals("15%", StatValueRenderer.render(spec(LoreValueFormat.PERCENT, 0, false, ""), 0.159));
    }

    @Test
    void percentIgnoresUnitBecauseThePercentSignIsAlreadyTheUnit() {
        assertEquals("50%", StatValueRenderer.render(spec(LoreValueFormat.PERCENT, 0, false, "秒"), 0.5));
    }

    @Test
    void flatKeepsSignWhenDeclared() {
        assertEquals("+2.5", StatValueRenderer.render(spec(LoreValueFormat.FLAT, 1, true, ""), 2.5));
        assertEquals("2.5", StatValueRenderer.render(spec(LoreValueFormat.FLAT, 1, false, ""), 2.5));
        assertEquals("-2.5", StatValueRenderer.render(spec(LoreValueFormat.FLAT, 1, false, ""), -2.5));
    }

    @Test
    void flatAppendsTheDeclaredUnit() {
        assertEquals("3秒", StatValueRenderer.render(spec(LoreValueFormat.FLAT, 0, false, "秒"), 3.9));
    }

    @Test
    void integerDropsTheFraction() {
        assertEquals("3", StatValueRenderer.render(spec(LoreValueFormat.INTEGER, 2, false, ""), 3.99));
        assertEquals("-3", StatValueRenderer.render(spec(LoreValueFormat.INTEGER, 2, false, ""), -3.99));
    }

    @Test
    void scalarIsPrefixedWithX() {
        assertEquals("x1.25", StatValueRenderer.render(spec(LoreValueFormat.SCALAR, 2, false, ""), 1.259));
    }

    @Test
    void decimalsAreCappedAtTwo() {
        assertEquals("1.23", StatValueRenderer.render(spec(LoreValueFormat.FLAT, 5, false, ""), 1.23456));
    }

    @Test
    void negativeValuesTruncateOnTheAbsoluteSideSoDisplayIsNeverMoreFavourable() {
        assertEquals(-1.23, StatValueRenderer.truncate(-1.2399, 2), 1e-9);
        assertEquals(1.23, StatValueRenderer.truncate(1.2399, 2), 1e-9);
    }

    @Test
    void plainDropsTrailingZeroes() {
        assertEquals("3", StatValueRenderer.plain(3.0));
        assertEquals("3.5", StatValueRenderer.plain(3.5));
        assertEquals("3.05", StatValueRenderer.plain(3.059));
    }

    @Test
    void zeroRendersWithoutASignEvenWhenSignIsDeclared() {
        assertEquals("+0", StatValueRenderer.render(spec(LoreValueFormat.FLAT, 0, true, ""), 0.0));
    }
}
