package com.trinityforge.progression;

import java.util.Objects;

/**
 * 図鑑エントリ1件のPDC文字列コーデック (2026-07-23-stat-gate-overhaul §6.3)。
 *
 * <p>新形式: {@code id|epochMillis|maxQualityPt}(パイプ区切り、エントリIDそのものは
 * {@code item:<catalogId>} / {@code mob:<ENTITY_TYPE>} でパイプを含まない前提)。
 * 旧形式({@code id} のみ、パイプなし)も読み替え可能: {@code epochMillis=0} / {@code maxQualityPt=0}
 * として扱う(初記録日時・最高品質ptが未記録だっただけで、記録自体は有効)。
 *
 * <p>書き込みは常に新形式。{@link PlayerData} は個々のエントリの意味を知らない、生の
 * {@code List<String>} の読み書きのみを担う({@link com.trinityforge.pdc.PlayerData#collectionEntries()}) —
 * このクラスがエンコード/デコードの唯一の場所。
 */
public record CollectionRecord(String id, long epochMillis, int maxQualityPt) {

    private static final char FIELD_SEPARATOR = '|';

    public CollectionRecord {
        Objects.requireNonNull(id, "id");
    }

    /**
     * 生のPDC文字列を解釈する。新形式({@code id|epoch|quality})はそのまま数値化し、
     * パイプ数が不正(1個/3個以上)または数値部分が不正(非数値等)な場合は、先頭セグメント(最初の
     * {@code '|'} より前)だけをIDとして正規化する後方互換フォールバックへ落とす。
     *
     * <p>2026-07-23 verifier指摘⑨: 以前はこのフォールバックが「壊れた文字列全体({@code '|'} を含む
     * ものも含め)」を新規IDとして採用していたため、{@link #encode} で再度 {@code id|0|0} 化されると
     * IDの中に元の {@code '|'} が残ったまま次回また同じ壊れ方で解釈され直し、パイプが際限なく増殖する
     * (＝図鑑エントリの実質的な増殖)事故になり得た。先頭セグメントのみを採用することでこれを断ち切る。
     * 先頭セグメントが空(例: 文字列が {@code '|'} で始まる)なら再現不能としてレコードごと落とす({@code null})。
     */
    public static CollectionRecord parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int first = raw.indexOf(FIELD_SEPARATOR);
        if (first < 0) {
            // 旧形式: idのみ。
            return new CollectionRecord(raw, 0L, 0);
        }
        int second = raw.indexOf(FIELD_SEPARATOR, first + 1);
        if (second < 0) {
            return normalizedFallback(raw, first);
        }
        if (raw.indexOf(FIELD_SEPARATOR, second + 1) >= 0) {
            // パイプが3個以上(余分なフィールド) -> 破損とみなし正規化フォールバック。
            return normalizedFallback(raw, first);
        }
        String id = raw.substring(0, first);
        try {
            long epoch = Long.parseLong(raw.substring(first + 1, second));
            int quality = Integer.parseInt(raw.substring(second + 1));
            return new CollectionRecord(id, epoch, quality);
        } catch (NumberFormatException ex) {
            return normalizedFallback(raw, first);
        }
    }

    /**
     * 壊れたレコードの正規化フォールバック: 先頭セグメント({@code '|'} を含まない)のみをIDとして採用し、
     * epoch/qualityは未記録(0)扱いにする。先頭セグメントが空ならレコードごと安全に落とす({@code null})。
     */
    private static CollectionRecord normalizedFallback(String raw, int firstSeparator) {
        String id = raw.substring(0, firstSeparator);
        if (id.isBlank()) {
            return null;
        }
        return new CollectionRecord(id, 0L, 0);
    }

    /** 常に新形式で直列化する。 */
    public String encode() {
        return id + FIELD_SEPARATOR + epochMillis + FIELD_SEPARATOR + maxQualityPt;
    }

    /** 同一IDのまま、より高い品質ptとより古い記録日時(初回記録日時を維持)でマージする。 */
    public CollectionRecord merge(long newEpochMillis, int newQualityPt) {
        long epoch = this.epochMillis > 0 ? this.epochMillis : newEpochMillis;
        int quality = Math.max(this.maxQualityPt, newQualityPt);
        return new CollectionRecord(id, epoch, quality);
    }
}
