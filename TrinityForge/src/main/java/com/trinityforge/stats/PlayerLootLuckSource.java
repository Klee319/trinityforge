package com.trinityforge.stats;

import org.bukkit.entity.Player;
import com.trinityforge.combat.PlayerStatAggregator;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Reads the player's {@code loot_luck} stat (装備+perk合算, 2026-07-23 stat-gate-overhaul §2 移行B5;
 * formerly the perk-only {@code power_luckbonus_add}) and converts it to a quality-mode bump for
 * non-craft acquisition paths.
 */
public final class PlayerLootLuckSource {

    private static final String LUCK_STAT = "LUCK_BONUS";
    private static final String LOOT_LUCK_KEY = StatKeys.canonical("loot_luck");

    private final boolean available;
    private final PlayerStatAggregator aggregator;

    public PlayerLootLuckSource(Logger log, PlayerStatAggregator aggregator) {
        Objects.requireNonNull(log, "log");
        this.aggregator = aggregator;
        this.available = aggregator != null;
    }

    boolean available() {
        return available;
    }

    String statKey() {
        return LUCK_STAT;
    }

    /** Stochastic integer bonus from fractional luck (same model as fishing-luck EV). */
    public int qualityModeBonus(Player player, ThreadLocalRandom rng) {
        return GatheringPolicy.expectedExtra(totalLuck(player), rng.nextDouble());
    }

    /**
     * Raw summed luck: {@code loot_luck}装備+perk合算 plus +1 per level of the vanilla LUCK potion effect
     * (amplifier 0 = Lv1 = +1.0). 0 when unavailable.
     */
    public double totalLuck(Player player) {
        if (player == null) {
            return 0.0;
        }
        double total = available
                ? Math.max(0.0, aggregator.aggregate(player).totalOf(LOOT_LUCK_KEY))
                : 0.0;
        return total + vanillaLuckEffectLevel(player);
    }

    /** Vanilla LUCK potion-effect level (amplifier+1), 0 when absent. */
    static double vanillaLuckEffectLevel(Player player) {
        org.bukkit.potion.PotionEffect effect =
                player.getPotionEffect(org.bukkit.potion.PotionEffectType.LUCK);
        return effect == null ? 0.0 : Math.max(0, effect.getAmplifier() + 1);
    }
}
