package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.MiningGimmickConfig;
import com.trinityforge.mining.MiningGimmickPolicy;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BrushableBlock;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Two unrelated block-break dedicated-effect consumers bundled into one listener because both are
 * simple {@link BlockBreakEvent} branches keyed by {@link Material} (not worth a whole file each):
 *
 * <ul>
 *   <li>{@code suspicious-block-respawn} (percent): breaking a suspicious sand/gravel block has a
 *       {@code valueSum}% chance to respawn the same block one tick later. Paper 1.21.11 has no
 *       dedicated "brush finish" event distinct from the block actually breaking, so a fully brushed
 *       suspicious block is a normal {@link BlockBreakEvent} here too (same handler covers both the
 *       brush-completion break and a plain punch-break).</li>
 *   <li>{@code spawner-silktouch-harvest} (flag): breaking a {@link Material#SPAWNER} with a
 *       silk-touch tool drops a plain SPAWNER item instead of vanilla's "drop nothing".
 *       <strong>Note (要調整)</strong>: the harvested spawner does NOT retain its configured
 *       {@code EntityType} — reproducing that would need custom NBT/PDC round-tripping through the
 *       drop item that nothing else in this codebase currently does, so it is deliberately left as a
 *       plain (pig-spawner-equivalent) SPAWNER item rather than guessed at.</li>
 * </ul>
 */
public final class MiningGimmickListener implements Listener {

    private static final String EFFECT_SPAWNER_HARVEST = "spawner-silktouch-harvest";
    private static final String SUSPICIOUS_RESPAWN_CHANCE_KEY = StatKeys.canonical("suspicious_respawn_chance");
    private static final long RESPAWN_DELAY_TICKS = 1L;

    private final Plugin plugin;
    private final DedicatedEffectsConfig dedicatedEffects;
    private final PlayerStatAggregator aggregator;
    private final MiningGimmickConfig miningGimmick;
    private final Enchantment silkTouch;

    public MiningGimmickListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                                 PlayerStatAggregator aggregator) {
        this(plugin, dedicatedEffects, aggregator, new MiningGimmickConfig());
    }

    public MiningGimmickListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                                 PlayerStatAggregator aggregator, MiningGimmickConfig miningGimmick) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.miningGimmick = Objects.requireNonNull(miningGimmick, "miningGimmick");
        this.silkTouch = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("silk_touch"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (SpellBreakGuard.isSpellBreak(event.getBlock())) {
            // 魔法(Ars)破壊の合成イベントには怪しいブロック復活/スポナー回収ギミックを一切与えない。
            return;
        }
        Material type = event.getBlock().getType();
        if (type == Material.SUSPICIOUS_SAND || type == Material.SUSPICIOUS_GRAVEL) {
            handleSuspiciousRespawn(event.getPlayer(), event.getBlock(), type);
            return;
        }
        if (type == Material.SPAWNER) {
            handleSpawnerHarvest(event);
        }
    }

    private void handleSuspiciousRespawn(Player player, Block block, Material type) {
        double chancePercent = aggregator.aggregate(player).totalOf(SUSPICIOUS_RESPAWN_CHANCE_KEY);
        if (!MiningGimmickPolicy.percentRoll(chancePercent, ThreadLocalRandom.current().nextDouble())) {
            return;
        }
        World world = block.getWorld();
        Location location = block.getLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Block current = world.getBlockAt(location);
            // Only respawn into empty space: if the player (or anything else) placed something there
            // in the 1-tick window, that placement must win rather than being silently overwritten.
            if (current.getType() == Material.AIR) {
                current.setType(type);
                // GTH-04: a block set via the API carries no loot table, so brushing it to completion
                // would silently yield nothing. Attach a real archaeology loot table (per-material, since
                // suspicious sand and suspicious gravel come from unrelated structures) with a fresh
                // random seed each time so the result can't be pre-computed/farmed by a player.
                if (current.getState() instanceof BrushableBlock brushable) {
                    LootTables table = type == Material.SUSPICIOUS_GRAVEL
                            ? miningGimmick.suspiciousGravelLootTable()
                            : miningGimmick.suspiciousSandLootTable();
                    brushable.setLootTable(table.getLootTable(), ThreadLocalRandom.current().nextLong());
                    brushable.update(true);
                }
            }
        }, RESPAWN_DELAY_TICKS);
    }

    private void handleSpawnerHarvest(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!dedicatedEffects.isActive(player, EFFECT_SPAWNER_HARVEST)) {
            return;
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!hasSilkTouch(tool)) {
            return;
        }
        event.setDropItems(false);
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(),
                new ItemStack(Material.SPAWNER, 1));
    }

    private boolean hasSilkTouch(ItemStack tool) {
        if (tool == null || !tool.hasItemMeta() || silkTouch == null) {
            return false;
        }
        ItemMeta meta = tool.getItemMeta();
        return meta.getEnchantLevel(silkTouch) > 0;
    }
}
