package com.trinityforge.gacha;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure approximation of the {@code gacha-rate-up} dedicated-effect (percent): {@code GachaDraw} and
 * {@code GachaPool}/{@code GachaEntry} are intentionally left unmodified (config-loaded pools must
 * stay exactly what {@code gacha.yml} describes), so the listener instead builds a temporary,
 * weight-boosted COPY of the pool and draws from that.
 *
 * <p><strong>Approximation</strong>: "rare slot" is approximated as every entry tied for the pool's
 * MINIMUM weight (the least-likely prize(s)); their weight is scaled by
 * {@code (1 + fractionBonus)}, rounded to the nearest integer and floored at 1 (a
 * {@link GachaEntry} weight must stay {@code > 0}). Every other entry's weight is unchanged. This
 * shifts relative odds toward the rarest row(s) without needing a config-declared "rarity tier"
 * concept that does not otherwise exist in the gacha schema.
 */
public final class GachaRateUp {

    private GachaRateUp() {
    }

    /**
     * Returns {@code pool} unchanged when {@code fractionBonus <= 0} or the pool has no entries;
     * otherwise returns a new {@link GachaPool} (same id, same entry order) with the minimum-weight
     * entries' weight boosted.
     *
     * <p>{@code fractionBonus} is a <b>fraction</b>, not a percent-point value (e.g. {@code 0.2} means
     * "+20%", not {@code 20}). The caller ({@code GachaListener}) reads this from
     * {@code PlayerStatAggregator#totalOf(gacha-rate-bonus)}, whose value has already been coerced to
     * {@code [0, 1]} by {@link com.trinityforge.stats.PercentStatNormalize} (RATE_KEYS includes
     * {@code gacha-rate-bonus}). Dividing this value by 100 again here would silently shrink a
     * configured {@code 20} (intended +20%) into an effective +0.2%.
     */
    public static GachaPool applyRateUp(GachaPool pool, double fractionBonus) {
        Objects.requireNonNull(pool, "pool");
        if (!Double.isFinite(fractionBonus) || fractionBonus <= 0.0 || pool.entries().isEmpty()) {
            return pool;
        }

        int minWeight = Integer.MAX_VALUE;
        for (GachaEntry entry : pool.entries()) {
            minWeight = Math.min(minWeight, entry.weight());
        }

        double multiplier = 1.0 + fractionBonus;
        List<GachaEntry> boosted = new ArrayList<>(pool.entries().size());
        for (GachaEntry entry : pool.entries()) {
            if (entry.weight() == minWeight) {
                int newWeight = (int) Math.max(1, Math.round(entry.weight() * multiplier));
                boosted.add(new GachaEntry(entry.itemId(), newWeight, entry.amount(), entry.qualityRandom()));
            } else {
                boosted.add(entry);
            }
        }
        // pityThreshold は天井の設計そのもの(config定義)であり、rate-up倍率とは独立に維持する。
        return new GachaPool(pool.id(), boosted, pool.pityThreshold());
    }
}
