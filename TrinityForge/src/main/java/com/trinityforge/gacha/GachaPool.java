package com.trinityforge.gacha;

import java.util.List;
import java.util.Objects;

/**
 * A named prize table ({@code gacha.yml pools.<id>}), referenced by one or more {@link GachaTicket}s.
 * Immutable; {@code entries} is defensively copied so a caller can never mutate the loaded table.
 *
 * @param id            pool id (matches the YAML section name)
 * @param entries       weighted prize rows; never empty for a successfully-parsed pool (the loader
 *                      {@code GachaConfig} skips a pool with no valid entries rather than construct one)
 * @param pityThreshold 天井(pity)回数。{@code 0} は無効(天井なし)。{@code > 0} のとき、同一プールで
 *                      これだけ連続して最高レア({@link GachaDraw#rarestEntries} = 最小weight枠)を
 *                      引けなかった場合、次回抽選は最高レア枠から強制確定させる
 *                      (ITEM_ECONOMY_SPEC CR-9 安全弁②)。負値は0にクランプする。
 */
public record GachaPool(String id, List<GachaEntry> entries, int pityThreshold) {

    public GachaPool {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        entries = entries == null ? List.of() : List.copyOf(entries);
        pityThreshold = Math.max(0, pityThreshold);
    }

    /** Convenience constructor for pools with no pity ceiling (pityThreshold = 0). */
    public GachaPool(String id, List<GachaEntry> entries) {
        this(id, entries, 0);
    }
}
