package com.trinityforge.skilltree.effects;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Pure {@code tier -> value} lookup table shared by every {@code SCALE} {@code feature:<id>} gimmick
 * config (2026-07-25 gather-rework-active-framework §1 item 1/2): {@code vein-mining}, {@code tree-fell},
 * {@code area-harvest}, {@code haste-active-mining}. A player's resolved tier (the highest
 * {@code feature:<id>} placement value they hold, via {@code DedicatedEffectsConfig#valueMax}) resolves to
 * the row of the largest defined tier {@code <=} that tier ("floor" — a player who unlocked tier 4 but only
 * tiers 1/3/5 are defined gets the tier-3 row). An {@link #empty()} table always resolves to
 * {@link Optional#empty()}, which every caller must treat as "use the legacy global scalar instead" — this
 * is the {@code tiers:} 未定義なら完全後方互換 rule (design doc §1 item 2 / §5 risk 1).
 *
 * <p>Bukkit-free and immutable so it is unit-testable with fixed inputs, same style as
 * {@code MiningGimmickPolicy}/{@code TreeFellingPolicy}.
 */
public final class TierTable<V> {

    private static final TierTable<?> EMPTY = new TierTable<>(new TreeMap<>());

    private final NavigableMap<Integer, V> rows;

    private TierTable(NavigableMap<Integer, V> rows) {
        this.rows = rows;
    }

    @SuppressWarnings("unchecked")
    public static <V> TierTable<V> empty() {
        return (TierTable<V>) EMPTY;
    }

    /** Copies {@code rows} defensively; a {@code null} or empty map yields {@link #empty()}. */
    public static <V> TierTable<V> of(Map<Integer, V> rows) {
        if (rows == null || rows.isEmpty()) {
            return empty();
        }
        return new TierTable<>(new TreeMap<>(rows));
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * The row of the largest defined tier {@code <=} {@code tier} ("floor"). Empty when the table has no
     * rows, or {@code tier <= 0}, or every defined tier is greater than {@code tier}.
     */
    public Optional<V> resolve(int tier) {
        if (rows.isEmpty() || tier <= 0) {
            return Optional.empty();
        }
        Map.Entry<Integer, V> entry = rows.floorEntry(tier);
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }

    /**
     * The defined tier key {@link #resolve(int)} actually used to answer {@code tier} (2026-07-28
     * 移行事故防止: 呼び出し側が「完全一致だったか floor フォールバックだったか」を区別して警告を
     * 出せるようにするための補助アクセサ)。Empty exactly when {@link #resolve(int)} is empty.
     */
    public Optional<Integer> resolvedKey(int tier) {
        if (rows.isEmpty() || tier <= 0) {
            return Optional.empty();
        }
        Map.Entry<Integer, V> entry = rows.floorEntry(tier);
        return entry == null ? Optional.empty() : Optional.of(entry.getKey());
    }
}
