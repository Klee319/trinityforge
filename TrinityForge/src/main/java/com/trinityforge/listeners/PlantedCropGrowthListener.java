package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.farming.FarmingCropCatalog;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code planted_crop_growth_bonus} consumer: プレイヤーが植えた作物の所有権をメモリ上に記録し、
 * 定期タスクで確率的に追加成長tickを与える。
 *
 * <p><b>重要: 完全にin-memory/セッション限定の実装</b>。サーバ再起動・リロードで所有権は失われる
 * (再起動後は誰の追加成長ボーナスも適用されなくなるだけで、作物自体やバニラ成長には影響しない)。
 * 永続化(PDC等)は意図的に行っていない — この機能はplaced-block trackerのような恒久追跡ではなく、
 * 「セッション中に植えた本人の作物を優遇する」程度のプレイ体験ボーナスという位置づけ。
 */
public final class PlantedCropGrowthListener implements Listener {

    private static final String PLANTED_CROP_GROWTH_BONUS = StatKeys.canonical("planted_crop_growth_bonus");
    private static final long GROWTH_TASK_INTERVAL_TICKS = 40L;

    private final Plugin plugin;
    private final PlayerStatAggregator aggregator;

    /** location(world+x+y+z)キー文字列 -> 植えたプレイヤーのUUID。境界: 破壊/成熟時に必ず除去する。 */
    private final Map<String, UUID> ownedCrops = new ConcurrentHashMap<>();

    public PlantedCropGrowthListener(Plugin plugin, PlayerStatAggregator aggregator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickOwnedCrops,
                GROWTH_TASK_INTERVAL_TICKS, GROWTH_TASK_INTERVAL_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (!FarmingCropCatalog.isCrop(block.getType())) return;
        Player player = event.getPlayer();
        double bonus = aggregator.aggregate(player).totalOf(PLANTED_CROP_GROWTH_BONUS);
        if (bonus <= 0.0) return;
        ownedCrops.put(key(block), player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ownedCrops.remove(key(event.getBlock()));
    }

    private void tickOwnedCrops() {
        if (ownedCrops.isEmpty()) return;
        var random = ThreadLocalRandom.current();
        ownedCrops.entrySet().removeIf(entry -> {
            Block block = fromKey(entry.getKey());
            if (block == null) {
                return true; // world不明 → 追跡打ち切り
            }
            // 未ロードチャンクを getType()/getBlockData() で強制ロードしない(オフライン所有者や遠方の
            // 作物が溜まると同期チャンクロードでサーバ負荷になる)。ロードされるまでスキップし追跡は維持。
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                return false;
            }
            if (!FarmingCropCatalog.isCrop(block.getType())
                    || !(block.getBlockData() instanceof Ageable ageable)) {
                return true; // 別ブロックに変わった → 追跡打ち切り
            }
            if (ageable.getAge() >= ageable.getMaximumAge()) {
                return true; // 既に成熟 → もう追加成長する必要なし
            }
            Player owner = Bukkit.getPlayer(entry.getValue());
            double bonus = owner == null ? 0.0 : aggregator.aggregate(owner).totalOf(PLANTED_CROP_GROWTH_BONUS);
            if (bonus > 0.0 && random.nextDouble() < Math.min(1.0, bonus)) {
                ageable.setAge(Math.min(ageable.getMaximumAge(), ageable.getAge() + 1));
                block.setBlockData(ageable);
            }
            return false;
        });
    }

    private static String key(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private Block fromKey(String key) {
        String[] parts = key.split(":", 4);
        if (parts.length != 4) return null;
        var world = Bukkit.getWorld(UUID.fromString(parts[0]));
        if (world == null) return null;
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return world.getBlockAt(x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
