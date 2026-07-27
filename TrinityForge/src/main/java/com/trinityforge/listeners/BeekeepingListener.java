package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 養蜂(畜産)スキルツリーのflag/percent系dedicated-effect consumer群
 * ({@code stats/farming-gimmick.yml}でチューニング):
 *
 * <ul>
 *   <li>{@code bee-no-aggro}(flag): 保有プレイヤーへ蜂が敵対しない。本体は
 *       {@link EntityTargetLivingEntityEvent}でターゲットが保有プレイヤーなら即キャンセル。加えて
 *       巣/養蜂箱の採取時、周囲の蜂の怒り({@link Bee#setAnger})をリセットするベストエフォート処理
 *       (要調整: 「絶対に怒らない」保証ではなく、都度ターゲットをキャンセルする方式との組み合わせ)。</li>
 *   <li>{@code hive-harvest-fortune}(養蜂幸運): 巣/養蜂箱からの採取物
 *       ({@link PlayerHarvestBlockEvent#getItemsHarvested()}、蜂蜜瓶/ハニカム)を、バニラの幸運と
 *       同じ考え方で追加ドロップさせる({@link #extraHarvests}、スタック上限でクランプ)。
 *       2026-07-26: 旧実装は集計時点で既にフラクションへ矯正済みの値をさらに
 *       {@code MiningGimmickPolicy.percentRoll}で100分割していたため実効確率が設定値の100分の1に
 *       なっていた確定バグがあった。乱数と直接比較する方式(crit-chance等と同じ)に修正。</li>
 * </ul>
 */
public final class BeekeepingListener implements Listener {

    private static final String EFFECT_BEE_NO_AGGRO = "bee-no-aggro";
    private static final String HIVE_HARVEST_FORTUNE_KEY = StatKeys.canonical("hive_harvest_fortune");

    private final DedicatedEffectsConfig dedicatedEffects;
    private final FarmingGimmickConfig gimmickConfig;
    private final PlayerStatAggregator aggregator;

    public BeekeepingListener(DedicatedEffectsConfig dedicatedEffects, FarmingGimmickConfig gimmickConfig,
                              PlayerStatAggregator aggregator) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Bee)) {
            return;
        }
        if (!(event.getTarget() instanceof Player target)) {
            return;
        }
        if (dedicatedEffects.isActive(target, EFFECT_BEE_NO_AGGRO)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHarvestBlock(PlayerHarvestBlockEvent event) {
        Material type = event.getHarvestedBlock().getType();
        if (type != Material.BEEHIVE && type != Material.BEE_NEST) {
            return;
        }
        Player player = event.getPlayer();
        if (dedicatedEffects.isActive(player, EFFECT_BEE_NO_AGGRO)) {
            calmNearbyBees(event);
        }
        double fortune = aggregator.aggregate(player).totalOf(HIVE_HARVEST_FORTUNE_KEY);
        int extra = extraHarvests(fortune, ThreadLocalRandom.current().nextDouble());
        if (extra > 0) {
            applyFortune(event, extra);
        }
    }

    /**
     * バニラの幸運と同じ考え方の追加ドロップ数を、フラクション {@code fortune}(例 0.20=20%)から算出する。
     * 整数部は確定で追加、端数部分は確率で+1される。負値/NaN等の不正値は0扱い。純粋な算術のみで
     * Bukkit非依存 — {@code randomRoll} は呼び出し側が乱数を注入できるようにするための引数
     * ([0,1) の一様乱数を渡す想定)。
     */
    static int extraHarvests(double fortune, double randomRoll) {
        double f = Double.isFinite(fortune) ? Math.max(0.0, fortune) : 0.0;
        int extra = (int) Math.floor(f);
        if (randomRoll < (f - extra)) {
            extra++;
        }
        return extra;
    }

    /** 採取物それぞれの個数を {@code (1 + extra)} 倍にする(素材ごとのスタック上限でクランプ)。 */
    private static void applyFortune(PlayerHarvestBlockEvent event, int extra) {
        for (ItemStack stack : event.getItemsHarvested()) {
            int multiplied = Math.min(stack.getAmount() * (1 + extra), stack.getMaxStackSize());
            stack.setAmount(multiplied);
        }
    }

    /** 周囲の蜂の怒りをリセットするベストエフォート処理(範囲は{@code bee-no-aggro.calm-radius}で設定)。 */
    private void calmNearbyBees(PlayerHarvestBlockEvent event) {
        double radius = gimmickConfig.beeCalmRadius();
        Location center = event.getHarvestedBlock().getLocation();
        for (Bee bee : event.getHarvestedBlock().getWorld()
                .getNearbyEntitiesByType(Bee.class, center, radius, radius, radius)) {
            bee.setAnger(0);
        }
    }
}
