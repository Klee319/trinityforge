package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.stats.StatKeys;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 繁殖系のstat consumer(3件): {@code breeding_vanilla_exp_bonus}(+{@code vanilla_exp_bonus})の
 * バニラEXP上乗せ、{@code breeding_extra_child_chance}の追加子供1体抽選、
 * {@code bred_animal_growth_bonus}の子供成長時間短縮。breeder(繁殖させたプレイヤー)がいない
 * (放牧トラップ等、非プレイヤー起因の繁殖)ケースはEXP/追加子供とも対象外。
 */
public final class BreedingBonusListener implements Listener {

    /** breeding_vanilla_exp_bonus+vanilla_exp_bonus が0でも0extraになる基準量。 */
    private static final int BASE_BREED_EXP = 5;
    private static final String VANILLA_EXP_BONUS = StatKeys.canonical("vanilla_exp_bonus");
    private static final String BREEDING_VANILLA_EXP_BONUS = StatKeys.canonical("breeding_vanilla_exp_bonus");
    private static final String BREEDING_EXTRA_CHILD_CHANCE = StatKeys.canonical("breeding_extra_child_chance");
    private static final String BRED_ANIMAL_GROWTH_BONUS = StatKeys.canonical("bred_animal_growth_bonus");
    private static final double MAX_GROWTH_BONUS_FRACTION = 0.9;

    private final PlayerStatAggregator aggregator;

    public BreedingBonusListener(PlayerStatAggregator aggregator) {
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        applyGrowthBonus(event);

        if (!(event.getBreeder() instanceof Player breeder) || excluded(breeder)) return;
        var totals = aggregator.aggregate(breeder);

        double expBonus = totals.totalOf(VANILLA_EXP_BONUS) + totals.totalOf(BREEDING_VANILLA_EXP_BONUS);
        if (expBonus > 0.0) {
            int extraExp = (int) Math.round(BASE_BREED_EXP * expBonus);
            if (extraExp > 0) {
                breeder.giveExp(extraExp);
            }
        }

        double extraChildChance = totals.totalOf(BREEDING_EXTRA_CHILD_CHANCE);
        if (extraChildChance > 0.0
                && ThreadLocalRandom.current().nextDouble() < Math.min(1.0, extraChildChance)) {
            spawnExtraChild(event);
        }
    }

    /** 生まれた子供の残り成長時間を短縮する(bred_animal_growth_bonus, 上限90%短縮)。 */
    private void applyGrowthBonus(EntityBreedEvent event) {
        if (!(event.getEntity() instanceof Ageable child) || !(event.getBreeder() instanceof Player breeder)
                || excluded(breeder)) {
            return;
        }
        if (child.getAge() >= 0) return; // 既に成体、または成長タイマーなし
        double bonus = Math.min(MAX_GROWTH_BONUS_FRACTION,
                Math.max(0.0, aggregator.aggregate(breeder).totalOf(BRED_ANIMAL_GROWTH_BONUS)));
        if (bonus <= 0.0) return;
        child.setAge((int) Math.round(child.getAge() * (1.0 - bonus)));
    }

    /** child と同種の追加ベビーを1体、同じ場所にスポーンする。 */
    private void spawnExtraChild(EntityBreedEvent event) {
        LivingEntity child = event.getEntity();
        Location location = child.getLocation();
        Entity extra = location.getWorld().spawnEntity(location, child.getType());
        if (extra instanceof Ageable extraAgeable) {
            extraAgeable.setBaby();
        }
    }

    private static boolean excluded(Player player) {
        GameMode gm = player.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }
}
