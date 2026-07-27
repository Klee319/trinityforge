package com.trinityforge.listeners;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.ParticleEffectService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * パーティクルシード合成 + 発動 (2026-07-23-stat-gate-overhaul §6.1)。
 *
 * <p>合成: 作業台の盤面にちょうど2アイテム(任意のツール/武器1つ + 定義済みシードアイテム1つ)が
 * 置かれたとき、動的に結果をツールのクローン + PDC刻印({@link ItemData#setParticleSeed}) に差し替える
 * (バニラの{@code Recipe}登録を経由しない — {@code CatalogWorkbenchListener} と同じ
 * {@code PrepareItemCraftEvent} 上書きパターン。取り出し時の素材消費は結果スロットが空でない限り
 * バニラのクラフト盤面が各スロット-1で自動処理するため追加のイベント処理は不要)。
 *
 * <p>発動 (2026-07-23 仕様§6.1準拠・verifier指摘⑨): シード刻印済みの道具で {@link BlockBreakEvent}
 * (ブロック破壊時) または {@link EntityDamageByEntityEvent}(攻撃時) を起こすたびに、刻印された
 * パーティクルを発生させる。頻度が高いため、プレイヤー毎に {@link #THROTTLE_MILLIS} の軽いスロットルを掛ける。
 */
public final class ParticleSeedListener implements Listener {

    /** 発動トリガーの軽いスロットル(5tick分)。頻度の高いブロック破壊/攻撃連打でのパーティクル過多を防ぐ。 */
    private static final long THROTTLE_MILLIS = 5L * 50L;

    private final SpecialRewardsConfig config;
    private final ConcurrentHashMap<UUID, Long> lastTriggerMillis = new ConcurrentHashMap<>();

    public ParticleSeedListener(SpecialRewardsConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (config.particleSeeds().isEmpty()) {
            return;
        }
        CraftingInventory inventory = event.getInventory();
        ItemStack[] matrix = inventory.getMatrix();
        List<Integer> filled = new ArrayList<>();
        for (int i = 0; i < matrix.length; i++) {
            if (matrix[i] != null && !matrix[i].getType().isAir()) {
                filled.add(i);
            }
        }
        if (filled.size() != 2) {
            return;
        }
        ItemStack a = matrix[filled.get(0)];
        ItemStack b = matrix[filled.get(1)];
        Result result = resolve(a, b);
        if (result == null) {
            result = resolve(b, a);
        }
        if (result == null) {
            return;
        }
        ItemStack stamped = result.tool().clone();
        ItemMeta meta = stamped.getItemMeta();
        if (meta == null) {
            return;
        }
        ItemData.of(meta).setParticleSeed(result.seed().id());
        stamped.setItemMeta(meta);
        inventory.setResult(stamped);
    }

    /** {@code tool} がツール/武器、{@code seedCandidate} が定義済みシードに一致すれば結果を返す。 */
    private Result resolve(ItemStack tool, ItemStack seedCandidate) {
        if (tool == null || tool.getType().isAir() || !ParticleSeedMatcher.isToolOrWeapon(tool.getType())) {
            return null;
        }
        for (Map.Entry<String, SpecialRewardsConfig.ParticleSeed> entry : config.particleSeeds().entrySet()) {
            if (ParticleSeedMatcher.matchesSeed(seedCandidate, entry.getValue().seedItem())) {
                return new Result(tool, entry.getValue());
            }
        }
        return null;
    }

    /** シード刻印ツールでのブロック破壊時に発動(§6.1)。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        triggerIfSeeded(player, player.getInventory().getItemInMainHand());
    }

    /** シード刻印武器での攻撃時に発動(§6.1)。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        triggerIfSeeded(player, player.getInventory().getItemInMainHand());
    }

    /** {@code item} がシード刻印済みなら(スロットル込みで)刻印パーティクルを発生させる。 */
    private void triggerIfSeeded(Player player, ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemData.of(item.getItemMeta()).particleSeed().ifPresent(seedId -> {
            SpecialRewardsConfig.ParticleSeed seed = config.particleSeeds().get(seedId);
            if (seed == null) {
                return;
            }
            if (!throttleReady(player.getUniqueId())) {
                return;
            }
            ParticleEffectService.burstAt(player, seed.particle(), seed.count(), 0.3);
        });
    }

    private boolean throttleReady(UUID playerId) {
        long now = System.currentTimeMillis();
        Long last = lastTriggerMillis.get(playerId);
        if (last != null && now - last < THROTTLE_MILLIS) {
            return false;
        }
        lastTriggerMillis.put(playerId, now);
        return true;
    }

    private record Result(ItemStack tool, SpecialRewardsConfig.ParticleSeed seed) {
    }
}
