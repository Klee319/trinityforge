package com.trinityforge.skilltree.runtime;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.stats.StatKeys;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Survival / reward perk consumers (2026-07-23 stat-gate-overhaul §2 移行B): reads the装備+perk合算
 * ({@link PlayerStatAggregator#aggregate}) instead of the old perk-only {@link NativePerkRewardResolver}.
 */
public final class NativeSurvivalPerkListener implements Listener {

    private static final String HEALTH_REGEN_BONUS = StatKeys.canonical("health_regen_bonus");
    private static final String HUNGER_SAVE_CHANCE = StatKeys.canonical("hunger_save_chance");
    private static final String MOB_DROP_BONUS = StatKeys.canonical("mob_drop_bonus");
    private static final String VANILLA_EXP_BONUS = StatKeys.canonical("vanilla_exp_bonus");
    private static final String KILL_VANILLA_EXP_BONUS = StatKeys.canonical("kill_vanilla_exp_bonus");

    private final PlayerStatAggregator aggregator;

    public NativeSurvivalPerkListener(PlayerStatAggregator aggregator) {
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        double bonus = aggregator.aggregate(player).totalOf(HEALTH_REGEN_BONUS);
        if (bonus <= 0.0) return;
        event.setAmount(event.getAmount() * (1.0 + bonus));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getFoodLevel() >= player.getFoodLevel()) return; // only drains
        double chance = aggregator.aggregate(player).totalOf(HUNGER_SAVE_CHANCE);
        if (chance <= 0.0) return;
        if (ThreadLocalRandom.current().nextDouble() < Math.min(0.9, chance)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeathDrops(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null) return;
        var totals = aggregator.aggregate(killer);

        // アイテムドロップ倍率とEXP倍率は独立に計算する: mob_drop_bonus が0でも
        // vanilla_exp_bonus/kill_vanilla_exp_bonus のみでEXPブーストが効くようにするため、
        // どちらか一方が0でも早期returnしない(旧実装はmob_drop_bonus<=0で丸ごとreturnしていた)。
        double dropMultAdd = totals.totalOf(MOB_DROP_BONUS);
        double dropFactor = Math.min(3.0, 1.0 + Math.max(0.0, dropMultAdd));
        if (dropFactor > 1.0) {
            List<ItemStack> drops = new ArrayList<>(event.getDrops());
            event.getDrops().clear();
            for (ItemStack drop : drops) {
                if (drop == null || drop.getType().isAir()) continue;
                ItemStack copy = drop.clone();
                int amount = Math.min(copy.getMaxStackSize() * 8,
                        Math.max(1, (int) Math.round(copy.getAmount() * dropFactor)));
                copy.setAmount(amount);
                event.getDrops().add(copy);
            }
        }

        // vanilla_exp_bonus(常時)は onVanillaExpGain(PlayerExpChangeEvent)側で全バニラXP源に一括適用する。
        // ここで加えるとドロップXPをオーブ回収時に二重適用してしまうため、キル固有分のみ乗せる。
        double expMultAdd = totals.totalOf(KILL_VANILLA_EXP_BONUS);
        double expFactor = 1.0 + Math.max(0.0, expMultAdd);
        if (expFactor > 1.0) {
            event.setDroppedExp((int) Math.round(event.getDroppedExp() * expFactor));
        }
    }

    /**
     * 常時バニラEXPブースト({@code vanilla_exp_bonus})を全てのバニラXP獲得源に一括適用する。
     * {@link PlayerExpChangeEvent} はオーブ回収・かまど精錬・釣り・取引・エンチャント瓶など
     * バニラXPの増加を一点で捕捉する。{@code Player#giveExp} 直接付与は本イベントを発火しないため、
     * TF独自付与(破壊/繁殖の合成EXP等)とは二重適用にならない。キル固有({@code kill_vanilla_exp_bonus})は
     * ドロップXP側で別途適用済みで、ここでは常時分のみを乗せる。
     * {@code PlayerExpChangeEvent} は Cancellable でないため ignoreCancelled は付けない。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVanillaExpGain(PlayerExpChangeEvent event) {
        int amount = event.getAmount();
        if (amount <= 0) return;
        double bonus = aggregator.aggregate(event.getPlayer()).totalOf(VANILLA_EXP_BONUS);
        if (bonus <= 0.0) return;
        event.setAmount((int) Math.round(amount * (1.0 + bonus)));
    }
}
