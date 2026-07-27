package com.trinityforge.stats;

import com.trinityforge.combat.PlayerStatAggregator;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Reads the killer's {@code mob_drop_quality} stat (装備+perk合算, 2026-07-23 stat-gate-overhaul §2 移行B6;
 * formerly the perk-only {@code power_mobdropbonus_add}) and converts it to a quality-mode bump for
 * mob-drop equipment stamps (spec: same mechanics as 幸運/{@link PlayerLootLuckSource} — +1 mode per whole
 * point, fractional part applied stochastically — but scoped to mob drops, which 幸運 deliberately does
 * not cover).
 */
public final class PlayerMobDropBonusSource {

    private static final String STAT = "MOB_DROP_BONUS";
    private static final String MOB_DROP_QUALITY_KEY = StatKeys.canonical("mob_drop_quality");

    private final boolean available;
    private final PlayerStatAggregator aggregator;

    public PlayerMobDropBonusSource(Logger log, PlayerStatAggregator aggregator) {
        Objects.requireNonNull(log, "log");
        this.aggregator = aggregator;
        this.available = aggregator != null;
    }

    boolean available() {
        return available;
    }

    String statKey() {
        return STAT;
    }

    /** Stochastic integer bonus from the fractional total (same EV model as 幸運). */
    public int qualityModeBonus(Player player, double unitRandom) {
        return GatheringPolicy.expectedExtra(totalBonus(player), unitRandom);
    }

    /** Convenience overload mirroring {@link PlayerLootLuckSource}'s fork-facing signature. */
    public int qualityModeBonus(Player player, java.util.concurrent.ThreadLocalRandom rng) {
        return qualityModeBonus(player, rng.nextDouble());
    }

    /** Raw summed {@code mob_drop_quality}装備+perk合算; 0 when unavailable. */
    public double totalBonus(Player player) {
        if (!available || player == null) {
            return 0.0;
        }
        return Math.max(0.0, aggregator.aggregate(player).totalOf(MOB_DROP_QUALITY_KEY));
    }
}
