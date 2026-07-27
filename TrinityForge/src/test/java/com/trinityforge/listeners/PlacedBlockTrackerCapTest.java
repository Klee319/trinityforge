package com.trinityforge.listeners;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-07-26 仕様悪用リスク分析で見つかった性能/DoS 穴の回帰テスト。
 *
 * <p>{@link PlacedBlockTracker} は「置き直しによる採取EXPファーム」を防ぐため、設置済みブロックを
 * チャンクPDCの {@code long[]} に線形格納している。上限が無かったため、チャンク（16×16×384 =
 * 98,304ブロック）を埋め立てると配列が約10万要素まで育ち、設置1回ごとの走査＋コピーと、
 * 一括伐採/一括採掘が<b>ブロックごとに</b>呼ぶ {@code isPlaced} がメインスレッドを圧迫していた。
 *
 * <p>ここでは純粋関数 {@link PlacedBlockTracker#appendCapped} だけを固定する
 * （Bukkit の Block/Chunk/PDC を必要としないので MockBukkit 非依存）。
 */
class PlacedBlockTrackerCapTest {

    @Test
    void appendsNormallyWhileUnderTheCap() {
        long[] result = PlacedBlockTracker.appendCapped(new long[] {1L, 2L}, 3L);

        assertEquals(3, result.length);
        assertEquals(1L, result[0]);
        assertEquals(2L, result[1]);
        assertEquals(3L, result[2]);
    }

    @Test
    void appendsToEmptyArray() {
        long[] result = PlacedBlockTracker.appendCapped(new long[0], 42L);

        assertEquals(1, result.length);
        assertEquals(42L, result[0]);
    }

    @Test
    void reachingTheCapExactlyStillAppendsWithoutEviction() {
        long[] existing = new long[PlacedBlockTracker.MAX_MARKS_PER_CHUNK - 1];
        for (int i = 0; i < existing.length; i++) {
            existing[i] = i;
        }

        long[] result = PlacedBlockTracker.appendCapped(existing, 999_999L);

        assertEquals(PlacedBlockTracker.MAX_MARKS_PER_CHUNK, result.length);
        assertEquals(0L, result[0], "上限ちょうどまでは最古を捨てない");
        assertEquals(999_999L, result[result.length - 1]);
    }

    @Test
    void evictsOldestOnceTheCapIsReached() {
        long[] existing = new long[PlacedBlockTracker.MAX_MARKS_PER_CHUNK];
        for (int i = 0; i < existing.length; i++) {
            existing[i] = i;
        }

        long[] result = PlacedBlockTracker.appendCapped(existing, 999_999L);

        assertEquals(PlacedBlockTracker.MAX_MARKS_PER_CHUNK, result.length,
                "上限を超えて伸び続けてはいけない(チャンクPDCが数百KBに膨らみtickスパイクになる)");
        assertEquals(1L, result[0], "最古(0)がFIFOで落ちて次の1が先頭になる");
        assertEquals(999_999L, result[result.length - 1], "新しいマークは必ず残る");
    }

    @Test
    void shrinksLegacyOversizedArraysBackToTheCap() {
        // 上限導入前のワールドには上限超えの配列が既に保存され得る。読み書きのたびに縮むこと。
        long[] existing = new long[PlacedBlockTracker.MAX_MARKS_PER_CHUNK * 3];
        for (int i = 0; i < existing.length; i++) {
            existing[i] = i;
        }

        long[] result = PlacedBlockTracker.appendCapped(existing, 999_999L);

        assertEquals(PlacedBlockTracker.MAX_MARKS_PER_CHUNK, result.length);
        assertEquals(999_999L, result[result.length - 1]);
        assertTrue(result[0] > 0, "残るのは末尾側(新しい方)のマーク");
    }
}
