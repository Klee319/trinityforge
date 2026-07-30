package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FoodGimmickConfig;
import com.trinityforge.food.FoodGimmickPolicy;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.StatKeys;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 農業ツリーA-α/β系「食事」ギミック(各 skilltree/*.yml ノードの dedicated-effects: フィールドで
 * 付与される flag/percent 系 consumer、stats/food-gimmick.yml でチューニング)をまとめて処理する。各effectは保有プレイヤーのみ発火し、
 * 非保有/config未設定時はバニラ挙動据え置き(no-op gate)。
 *
 * <ul>
 *   <li>{@code junkfood-immunity}(flag): ゴミ食({@link FoodGimmickConfig#isJunkFood(ItemStack)})を
 *       食べた際にバニラが付与するデバフ系ポーション効果を打ち消す。{@link EntityPotionEffectEvent}の
 *       {@code Cause.FOOD}を使い、{@link FoodGimmickConfig#junkfoodImmunityCancelledEffects()}の
 *       許可リストに載っている種類のみキャンセルする(金リンゴ/金人参のバフ効果もCause.FOODで発火する
 *       ため、許可リスト方式でなければ誤って打ち消してしまう)。</li>
 *   <li>{@code junkfood-inversion}(LEVEL, %; 2026-07-27 農業「ゴミ食」段階化): ゴミ食は隠し満腹度
 *       (saturation)回復量UP、非ゴミ食はDOWN。{@link FoodLevelChangeEvent#getItem()}で消費食料を特定し、
 *       {@link FoodGimmickPolicy}でゴミ/非ゴミ判定した上でsaturationを直接補正する。基準量
 *       ({@link FoodGimmickConfig#junkfoodInversionJunkSaturationBonus()} /
 *       {@link FoodGimmickConfig#junkfoodInversionNonJunkSaturationPenalty()})に、保持ノード中の最大
 *       {@code value}(%、{@link DedicatedEffectsConfig#valueMax})を倍率として掛ける
 *       ({@code value:100}が基準量そのもの)。{@code junk-food-restore-boost}と同じ「保持ノードの最大
 *       valueを採用する」流儀(A-alpha-1→A-alpha-2のprerequisite連結によりA-alpha-2保持者はA-alpha-1の
 *       配置も保持しているため、tierテーブル無しでそのまま最大%が引ける)。</li>
 *   <li>{@code no-food-consume-chance}(percent, stat key {@code food-save-chance}): 食事してもアイテムを
 *       消費しない確率。{@link PlayerItemConsumeEvent#setReplacement}で消費前の全量スタックに差し替え、
 *       満腹度回復自体は通常通り(バニラの{@link FoodLevelChangeEvent}経路)与える。
 *       {@link org.bukkit.Material#isEdible()}が真のアイテムのみ対象(ポーション等の飲食は対象外)。
 *       2026-07-27: 以前は{@link com.trinityforge.stats.PercentStatNormalize}で既にフラクションへ矯正
 *       済みの値を、さらに0-100スケール前提の{@code MiningGimmickPolicy.percentRoll}に通していたため
 *       実効確率が設定値の100分の1になっていた確定バグがあった
 *       ({@link BeekeepingListener}の{@code hive-harvest-fortune}と同じバグ種)。乱数と直接比較する方式
 *       ({@link com.trinityforge.combat.CritResolver}と同じ流儀)に修正済み。</li>
 *   <li>{@code satiety-buff}(flag): 完全食の隠し満腹度(saturation)回復量UP。
 *       {@link FoodLevelChangeEvent}でsaturationに追加加算。</li>
 *   <li><strong>カスタム食料(custom-foods, どのperk/featureにもゲートされない)</strong>: 消費したアイテムが
 *       {@link FoodGimmickConfig#customFood(String)}に一致する場合、満腹度/隠し満腹度をREPLACE方式
 *       (ベースMaterialのバニラ栄養値を無視し、設定値ちょうどに置き換える)で適用する。満腹度は
 *       {@link FoodLevelChangeEvent#setFoodLevel(int)}で直接確定できる(イベント返却値がそのまま
 *       {@code FoodData#foodLevel}へ書き込まれるため)が、隠し満腹度(saturation)はイベント終了直後に
 *       バニラが元アイテムの{@code FoodProperties}(nutrition×saturationModifier)で上書きしてしまうため、
 *       イベント内での{@code setSaturation}は無効化される。そのためsaturationのみ次tickへ
 *       ({@code runTask})ずらし、バニラの上書きが完了した後に確定値を再設定する。解放はスキルツリー側の
 *       {@code recipe:}ゲート(クラフト可否)で管理し、このリスナー自体は常時有効(flagゲート無し)。</li>
 * </ul>
 */
public final class FoodGimmickListener implements Listener {

    private static final String EFFECT_JUNKFOOD_IMMUNITY = "junkfood-immunity";
    private static final String EFFECT_JUNKFOOD_INVERSION = "junkfood-inversion";
    private static final String EFFECT_SATIETY_BUFF = "satiety-buff";
    private static final String FOOD_SAVE_CHANCE_KEY = StatKeys.canonical("food_save_chance");
    private static final int MAX_FOOD_LEVEL = 20;

    private final Plugin plugin;
    private final DedicatedEffectsConfig dedicatedEffects;
    private final FoodGimmickConfig foodGimmick;
    private final PlayerStatAggregator aggregator;

    public FoodGimmickListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                               FoodGimmickConfig foodGimmick, PlayerStatAggregator aggregator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.foodGimmick = Objects.requireNonNull(foodGimmick, "foodGimmick");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    /** {@code no-food-consume-chance}: 食事してもアイテムを消費しない確率。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (event.isCancelled()) {
            return;
        }
        ItemStack original = event.getItem();
        if (original == null || !original.getType().isEdible()) {
            return;
        }
        Player player = event.getPlayer();
        // PercentStatNormalize.RATE_KEYS already coerces this to a [0,1] fraction at aggregation time
        // (e.g. 20 -> 0.2). Compare the fraction directly against the roll (same idiom as CritResolver /
        // BreedingBonusListener#BREEDING_EXTRA_CHILD_CHANCE) instead of routing it through a
        // percentRoll-style helper that expects a 0-100 scale, which would silently divide it by 100 again.
        double foodSaveChanceFraction = aggregator.aggregate(player).totalOf(FOOD_SAVE_CHANCE_KEY);
        if (!Double.isFinite(foodSaveChanceFraction) || foodSaveChanceFraction <= 0.0) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= Math.min(1.0, foodSaveChanceFraction)) {
            return;
        }
        // Roll succeeded: keep the full pre-consume stack in hand instead of the vanilla decremented
        // one. The food-level/saturation gain is untouched — it still flows through the normal
        // FoodLevelChangeEvent path below since this event only controls what remains in the hand.
        event.setReplacement(original.clone());
    }

    /**
     * カスタム食料(custom-foods) + {@code junkfood-inversion} + {@code satiety-buff}:
     * 満腹度/隠し満腹度の確定・補正。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }

        Optional<FoodGimmickConfig.CustomFood> customFood = CrossPluginItemResolver.idOf(item)
                .flatMap(foodGimmick::customFood);
        if (customFood.isPresent()) {
            applyCustomFood(event, player, item, customFood.get());
            return; // カスタム食料は独自のREPLACE適用(下記メソッド内でjunkfood-inversion/satiety-buffの
                     // 追加補正も一緒に次tickへ折り込む)ので、この先の汎用加算経路には進まない。
        }

        double saturationAdjustment = 0.0;
        OptionalDouble inversionPercent = dedicatedEffects.valueMax(player, EFFECT_JUNKFOOD_INVERSION);
        if (inversionPercent.isPresent()) {
            boolean junk = foodGimmick.isJunkFood(item);
            double multiplier = inversionMultiplier(inversionPercent.getAsDouble());
            saturationAdjustment += FoodGimmickPolicy.inversionSaturationAdjustment(junk,
                    foodGimmick.junkfoodInversionJunkSaturationBonus() * multiplier,
                    foodGimmick.junkfoodInversionNonJunkSaturationPenalty() * multiplier);
        }
        if (dedicatedEffects.isActive(player, EFFECT_SATIETY_BUFF)) {
            saturationAdjustment += foodGimmick.satietyBuffSaturationBonus();
        }
        if (saturationAdjustment == 0.0) {
            return;
        }
        float adjusted = (float) Math.max(0.0, player.getSaturation() + saturationAdjustment);
        player.setSaturation(adjusted);
    }

    /**
     * カスタム食料のREPLACE適用: 満腹度は{@link FoodLevelChangeEvent#setFoodLevel(int)}で即時確定し、
     * 隠し満腹度(saturation)はバニラの上書き(元アイテムのFoodProperties由来)が完了した後の次tickで
     * 確定値へ再設定する。{@code junkfood-inversion}/{@code satiety-buff}を保有しているプレイヤーは
     * その追加補正もこの確定値に折り込む(「further-adjust」= 加算的に上乗せする想定)。
     */
    private void applyCustomFood(FoodLevelChangeEvent event, Player player, ItemStack item,
                                 FoodGimmickConfig.CustomFood customFood) {
        int preFood = player.getFoodLevel();
        double preSaturation = player.getSaturation();

        int targetFood = Math.min(MAX_FOOD_LEVEL, preFood + customFood.foodLevel());
        event.setFoodLevel(targetFood);

        double perkAdjustment = 0.0;
        OptionalDouble inversionPercent = dedicatedEffects.valueMax(player, EFFECT_JUNKFOOD_INVERSION);
        if (inversionPercent.isPresent()) {
            boolean junk = foodGimmick.isJunkFood(item);
            double multiplier = inversionMultiplier(inversionPercent.getAsDouble());
            perkAdjustment += FoodGimmickPolicy.inversionSaturationAdjustment(junk,
                    foodGimmick.junkfoodInversionJunkSaturationBonus() * multiplier,
                    foodGimmick.junkfoodInversionNonJunkSaturationPenalty() * multiplier);
        }
        if (dedicatedEffects.isActive(player, EFFECT_SATIETY_BUFF)) {
            perkAdjustment += foodGimmick.satietyBuffSaturationBonus();
        }

        // targetFood already caps this at the new food level (per the REPLACE formula), so the scheduled
        // task below applies it verbatim — no live re-read of the player's food level is needed (and a
        // live re-read would be unreliable: this handler runs before the real food-level assignment from
        // the event actually lands, and is not guaranteed to have landed the moment the next tick starts).
        double targetSaturation = Math.min(targetFood,
                Math.max(0.0, preSaturation + customFood.saturation() + perkAdjustment));
        UUID playerId = player.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player online = plugin.getServer().getPlayer(playerId);
            if (online == null || !online.isOnline()) {
                return;
            }
            online.setSaturation((float) targetSaturation);
        });
    }

    /**
     * {@code junkfood-inversion}(LEVEL, %) の保持ノード最大valueを、food-gimmick.yml基準量への倍率に
     * 変換する({@code value:100}で基準量そのもの、{@code value:150}で1.5倍)。負値/非有限値は0として扱う
     * (設定ミスで補正が暴走しないためのガード、{@link FoodGimmickPolicy}の他のガードと同じ方針)。
     */
    private static double inversionMultiplier(double percentValue) {
        if (!Double.isFinite(percentValue) || percentValue < 0.0) {
            return 0.0;
        }
        return percentValue / 100.0;
    }

    /** {@code junkfood-immunity}: ゴミ食後にバニラが付与するデバフ系ポーション効果を打ち消す。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (event.getCause() != EntityPotionEffectEvent.Cause.FOOD) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        PotionEffectType type = event.getModifiedType();
        if (type == null || !foodGimmick.junkfoodImmunityCancelledEffects().contains(type)) {
            return;
        }
        if (!dedicatedEffects.isActive(player, EFFECT_JUNKFOOD_IMMUNITY)) {
            return;
        }
        event.setCancelled(true);
    }
}
