package com.trinityforge.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** PDC文字列コーデックの往復・後方互換読み替えを検証する (2026-07-23-stat-gate-overhaul §6.3)。 */
class CollectionRecordTest {

    @Test
    void parsesLegacyBareIdAsZeroEpochAndQuality() {
        CollectionRecord record = CollectionRecord.parse("item:core_ember");
        assertEquals("item:core_ember", record.id());
        assertEquals(0L, record.epochMillis());
        assertEquals(0, record.maxQualityPt());
    }

    @Test
    void parsesNewFormatFields() {
        CollectionRecord record = CollectionRecord.parse("mob:ZOMBIE|1690000000000|77");
        assertEquals("mob:ZOMBIE", record.id());
        assertEquals(1690000000000L, record.epochMillis());
        assertEquals(77, record.maxQualityPt());
    }

    @Test
    void encodeRoundTrips() {
        CollectionRecord original = new CollectionRecord("item:x", 123L, 45);
        assertEquals(original, CollectionRecord.parse(original.encode()));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("2026-07-23 verifier指摘⑨: 数値部分が不正なら先頭セグメントのみをIDに"
            + "正規化する(パイプを含む文字列全体を新規IDにしない — 再encodeでのパイプ増殖を防ぐ)")
    void malformedNumericFieldsNormalizeToLeadingSegmentOnly() {
        CollectionRecord record = CollectionRecord.parse("item:x|not-a-number|5");
        assertEquals("item:x", record.id());
        assertEquals(0L, record.epochMillis());
        assertEquals(0, record.maxQualityPt());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("正規化されたレコードは再encodeしてもパイプが増殖しない(不動点)")
    void normalizedFallbackIsAFixedPointUnderReEncode() {
        CollectionRecord first = CollectionRecord.parse("item:x|not-a-number|5");
        CollectionRecord reparsed = CollectionRecord.parse(first.encode());
        assertEquals(first, reparsed, "正規化後は再parse/encodeしても同じレコードに収束する");
        assertEquals("item:x", reparsed.id());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("パイプ3個以上(余分なフィールド)も先頭セグメントのみへ正規化する")
    void tooManySeparatorsNormalizeToLeadingSegmentOnly() {
        CollectionRecord record = CollectionRecord.parse("item:x|123|45|extra");
        assertEquals("item:x", record.id());
        assertEquals(0L, record.epochMillis());
        assertEquals(0, record.maxQualityPt());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("先頭セグメントが空の壊れたレコードはレコードごと安全に落とす")
    void emptyLeadingSegmentDropsTheRecordEntirely() {
        assertNull(CollectionRecord.parse("|not-a-number|5"));
    }

    @Test
    void blankRawYieldsNull() {
        assertNull(CollectionRecord.parse(""));
        assertNull(CollectionRecord.parse(null));
    }

    @Test
    void mergeKeepsFirstEpochAndTakesMaxQuality() {
        CollectionRecord existing = new CollectionRecord("item:x", 100L, 30);
        CollectionRecord merged = existing.merge(200L, 60);
        assertEquals(100L, merged.epochMillis(), "初記録日時は上書きしない");
        assertEquals(60, merged.maxQualityPt(), "より高い品質ptへ更新");

        CollectionRecord mergedLower = existing.merge(200L, 10);
        assertEquals(30, mergedLower.maxQualityPt(), "既録より低い品質は無視");
    }
}
