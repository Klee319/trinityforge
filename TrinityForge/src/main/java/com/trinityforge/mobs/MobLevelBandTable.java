package com.trinityforge.mobs;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Pure {@code min-level -> value} floor-lookup table for {@code combat/mob-level-table.yml}
 * (level-band drop/EXP rules, ダンジョン限定切替も含む要望). A mob's resolved combat level
 * (from {@code MobData#level()}, which starts at {@code 0} — field mobs commonly spawn at level 0
 * before coordinate scaling) resolves to the row of the largest defined band whose {@code min-level}
 * is {@code <=} that level ("floor"). {@link #empty()} always resolves to {@link Optional#empty()},
 * which every caller must treat as "no level-table rule for this kill" — this is the tiers 未設定なら
 * 完全後方互換 rule.
 *
 * <p>Deliberately NOT {@code com.trinityforge.skilltree.effects.TierTable}: that class rejects any
 * lookup key {@code <= 0} (its domain is player-unlocked *tiers*, which start at 1), but mob combat
 * levels legitimately start at {@code 0} (an untouched field zombie at world spawn is level 0), so
 * reusing it verbatim would make a {@code min-level: 0} band unreachable. This is the same
 * floor-lookup shape with that one guard relaxed to {@code level < 0}.
 *
 * <p>Bukkit-free and immutable so it is unit-testable with fixed inputs, same style as
 * {@link com.trinityforge.skilltree.effects.TierTable}.
 */
public final class MobLevelBandTable<V> {

    private static final MobLevelBandTable<?> EMPTY = new MobLevelBandTable<>(new TreeMap<>());

    private final NavigableMap<Integer, V> rows;

    private MobLevelBandTable(NavigableMap<Integer, V> rows) {
        this.rows = rows;
    }

    @SuppressWarnings("unchecked")
    public static <V> MobLevelBandTable<V> empty() {
        return (MobLevelBandTable<V>) EMPTY;
    }

    /** Copies {@code rows} defensively; a {@code null} or empty map yields {@link #empty()}. */
    public static <V> MobLevelBandTable<V> of(Map<Integer, V> rows) {
        if (rows == null || rows.isEmpty()) {
            return empty();
        }
        return new MobLevelBandTable<>(new TreeMap<>(rows));
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * The row of the largest defined {@code min-level <= level} ("floor"). Empty when the table has
     * no rows, {@code level < 0}, or every defined band's {@code min-level} is greater than {@code level}
     * (level below the lowest defined band).
     */
    public Optional<V> resolve(int level) {
        if (rows.isEmpty() || level < 0) {
            return Optional.empty();
        }
        Map.Entry<Integer, V> entry = rows.floorEntry(level);
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }
}
