package com.trinityforge.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * グリフ解放数の数え方。
 *
 * <p><b>同じ PDC キー {@code arspaper:unlocked_glyphs} に 2 つの形式が書かれうる。</b>
 * 実際に書いているのは {@code ScribingTableGui} の Gson JSON 配列だが、fork には同じキーを
 * U+001F 区切りで読む {@code UnlockedGlyphs} も残っている。片方しか見ない実装にすると
 * もう片方の形式で解放数が黙って 0 になるため、両方を受ける。
 */
class RankingStatsServiceGlyphCountTest {

    /** fork の {@code UnlockedGlyphs} と同じ組み立て方（生の制御文字をソースに置かない）。 */
    private static final String US = String.valueOf((char) 0x1F);

    @Test
    @DisplayName("JSON 配列の要素数を数える（現行の書き込み形式）")
    void countsJsonArrayElements() {
        assertEquals(3, RankingStatsService.countGlyphs(
                "[\"ars_nouveau:glyph_break\",\"ars_nouveau:glyph_harvest\",\"ars_nouveau:glyph_ignite\"]"));
    }

    @Test
    @DisplayName("空の JSON 配列は 0")
    void emptyJsonArrayIsZero() {
        assertEquals(0, RankingStatsService.countGlyphs("[]"));
    }

    @Test
    @DisplayName("前後に空白があっても数えられる")
    void tolerantOfSurroundingWhitespace() {
        assertEquals(2, RankingStatsService.countGlyphs("  [\"a\",\"b\"]  "));
    }

    @Test
    @DisplayName("U+001F 区切りの旧形式も数える")
    void countsLegacyUnitSeparatorFormat() {
        assertEquals(3, RankingStatsService.countGlyphs("glyph_a" + US + "glyph_b" + US + "glyph_c"));
    }

    @Test
    @DisplayName("旧形式の単一要素は 1（区切り文字が 1 つも無いケース）")
    void singleLegacyTokenIsOne() {
        assertEquals(1, RankingStatsService.countGlyphs("glyph_only"));
    }

    @Test
    @DisplayName("旧形式の空要素は数えない")
    void blankLegacyTokensAreSkipped() {
        assertEquals(2, RankingStatsService.countGlyphs("glyph_a" + US + US + "glyph_b" + US));
    }

    @Test
    @DisplayName("null / 空文字は 0")
    void nullAndEmptyAreZero() {
        assertEquals(0, RankingStatsService.countGlyphs(null));
        assertEquals(0, RankingStatsService.countGlyphs(""));
        assertEquals(0, RankingStatsService.countGlyphs("   "));
    }

    @Test
    @DisplayName("壊れた JSON は 0（例外を投げない）")
    void brokenJsonIsZeroNotAnException() {
        assertEquals(0, RankingStatsService.countGlyphs("[\"a\","));
        assertEquals(0, RankingStatsService.countGlyphs("[not json at all"));
    }

    @Test
    @DisplayName("1 文字ずつ分割されていないこと（区切り文字の指定漏れ回帰）")
    void doesNotSplitEveryCharacter() {
        // GLYPH_LEGACY_DELIMITER_REGEX が空文字だと 8 になる。区切りが正しければ 1。
        assertEquals(1, RankingStatsService.countGlyphs("abcdefgh"));
    }
}
