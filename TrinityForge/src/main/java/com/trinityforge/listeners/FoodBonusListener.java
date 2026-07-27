package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FoodGimmickConfig;
import com.trinityforge.food.FoodGimmickPolicy;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 食事系のstat consumer(2件): {@code food_restore_bonus}(満腹度の回復量を割増)と
 * {@code hidden_saturation_bonus}(隠し満腹度=飽和度を割増)。どちらもフラクション値(0.2=+20%)。
 *
 * <p>2026-07-25 farming.yml A-alpha-2「ゴミの満腹度回復量UP・ゴミ以外の満腹度回復量を戻す」
 * ({@code feature:junk-food-restore-boost})が有効な場合: ゴミ食(判定は
 * {@code stats/food-gimmick.yml junk-food-materials}を再利用)には {@code food_restore_bonus} に
 * ノードのvalue(%)を追加で上乗せし、非ゴミ食には{@code food_restore_bonus}そのものを適用しない
 * (「戻す」= 通常のバニラ回復量に戻す)。ノード未保持なら従来通り{@code food_restore_bonus}を
 * 全食料へ一律適用する(後方互換)。
 */
public final class FoodBonusListener implements Listener {

    private static final String FOOD_RESTORE_BONUS = StatKeys.canonical("food_restore_bonus");
    private static final String HIDDEN_SATURATION_BONUS = StatKeys.canonical("hidden_saturation_bonus");
    private static final String EFFECT_JUNK_FOOD_RESTORE_BOOST = "junk-food-restore-boost";

    private final Plugin plugin;
    private final PlayerStatAggregator aggregator;
    private final DedicatedEffectsConfig dedicatedEffects;
    private final FoodGimmickConfig foodGimmickConfig;
    // PlayerItemConsumeEvent(HIGH)からFoodLevelChangeEvent(同tick、HIGH)へ「何を食べたか」を橋渡し
    // する短命マップ。FoodLevelChangeEvent自体は消費アイテムを保持しないため必要。
    private final ConcurrentHashMap<UUID, Material> pendingConsumed = new ConcurrentHashMap<>();

    public FoodBonusListener(Plugin plugin, PlayerStatAggregator aggregator) {
        this(plugin, aggregator, null, null);
    }

    /**
     * @param dedicatedEffects  {@code feature:junk-food-restore-boost}の解決用。{@code null}なら
     *                          ゴミ食判定を一切行わず、従来通り{@code food_restore_bonus}を一律適用する。
     * @param foodGimmickConfig ゴミ食一覧(junk-food-materials)の解決用。{@code null}は上と同様。
     */
    public FoodBonusListener(Plugin plugin, PlayerStatAggregator aggregator,
                              DedicatedEffectsConfig dedicatedEffects, FoodGimmickConfig foodGimmickConfig) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.dedicatedEffects = dedicatedEffects;
        this.foodGimmickConfig = foodGimmickConfig;
    }

    /** {@link #onFoodChange}が「何を食べたか」を読めるよう、消費アイテムのMaterialを橋渡しする。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsumeTrackItem(PlayerItemConsumeEvent event) {
        pendingConsumed.put(event.getPlayer().getUniqueId(), event.getItem().getType());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        int oldLevel = player.getFoodLevel();
        int newLevel = event.getFoodLevel();
        if (newLevel <= oldLevel) return; // 回復(増加)のみ対象。減少はhunger_save_chance側の担当。

        Material consumed = pendingConsumed.remove(player.getUniqueId());
        double effectiveBonus = resolveFoodRestoreBonus(player, consumed);
        if (effectiveBonus <= 0.0) return;
        int gained = newLevel - oldLevel;
        int boosted = (int) Math.round(gained * (1.0 + effectiveBonus));
        event.setFoodLevel(Math.min(20, oldLevel + boosted));
    }

    /** Drops the pending-consumed entry so the map never grows unbounded over server uptime. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pendingConsumed.remove(event.getPlayer().getUniqueId());
    }

    private double resolveFoodRestoreBonus(Player player, Material consumed) {
        double genericBonus = aggregator.aggregate(player).totalOf(FOOD_RESTORE_BONUS);
        if (dedicatedEffects == null || foodGimmickConfig == null) {
            return genericBonus; // A-alpha-2未配線: 従来通り一律適用。
        }
        OptionalDouble junkBoostPercent = dedicatedEffects.valueMax(player, EFFECT_JUNK_FOOD_RESTORE_BOOST);
        if (junkBoostPercent.isEmpty()) {
            return genericBonus; // A-alpha-2未保持: 従来通り一律適用。
        }
        boolean junk = FoodGimmickPolicy.isJunkFood(consumed, foodGimmickConfig.junkFoodMaterials());
        if (junk) {
            return genericBonus + Math.max(0.0, junkBoostPercent.getAsDouble()) / 100.0;
        }
        return 0.0; // 「非ゴミ食の満腹度回復量を戻す」= food_restore_bonusを適用しない。
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        double bonus = aggregator.aggregate(player).totalOf(HIDDEN_SATURATION_BONUS);
        if (bonus <= 0.0) return;
        // バニラの満腹度/飽和度反映は同tick内でこのイベントの後に起きるため、1tick後に上乗せする。
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            float cap = player.getFoodLevel();
            float boosted = (float) (player.getSaturation() * (1.0 + bonus));
            player.setSaturation(Math.min(cap, boosted));
        });
    }
}
