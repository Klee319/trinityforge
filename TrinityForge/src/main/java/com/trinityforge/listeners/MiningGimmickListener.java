package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.MiningGimmickConfig;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
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
 *       {@code suspicious-respawn-chance} fraction chance to respawn the same block one tick later.
 *       Paper 1.21.11 has no dedicated "brush finish" event distinct from the block actually breaking,
 *       so a fully brushed suspicious block is a normal {@link BlockBreakEvent} here too (same handler
 *       covers both the brush-completion break and a plain punch-break).
 *       2026-07-27: this used to route the already-fraction-coerced ({@link com.trinityforge.stats.PercentStatNormalize})
 *       value through {@code MiningGimmickPolicy.percentRoll} (which expects a 0-100 scale), dividing it
 *       by 100 a second time and making the effective chance 1/100th of the configured value — same bug
 *       class as {@link BeekeepingListener}'s {@code hive-harvest-fortune} fix. Now compares the fraction
 *       directly against the roll, same idiom as {@link com.trinityforge.combat.CritResolver}.</li>
 *   <li>{@code spawner-silktouch-harvest} (flag): breaking a {@link Material#SPAWNER} with a
 *       silk-touch tool drops a SPAWNER item carrying the <b>whole</b> {@link CreatureSpawner} block
 *       state through {@link BlockStateMeta} (see {@link #spawnerItem}: copying only the entity type
 *       both lost {@code spawnPotentials}/delays and — when {@code getSpawnedType()} returned
 *       {@code null} — silently produced an EMPTY spawner).
 *       2026-08-01: player-placed spawners used to be excluded entirely, which made a re-placed
 *       spawner <b>vanish on break</b> (no drop from us, and vanilla drops nothing either). They are
 *       harvestable again; only the vanilla spawner EXP stays suppressed, since that (unlike the item)
 *       really can be farmed by repeated place/break.</li>
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
    private final PlacedBlockTracker placedBlocks;
    private final Enchantment silkTouch;

    public MiningGimmickListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                                 PlayerStatAggregator aggregator) {
        this(plugin, dedicatedEffects, aggregator, new MiningGimmickConfig(),
                new PlacedBlockTracker(plugin));
    }

    public MiningGimmickListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                                 PlayerStatAggregator aggregator, MiningGimmickConfig miningGimmick) {
        this(plugin, dedicatedEffects, aggregator, miningGimmick, new PlacedBlockTracker(plugin));
    }

    public MiningGimmickListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                                 PlayerStatAggregator aggregator, MiningGimmickConfig miningGimmick,
                                 PlacedBlockTracker placedBlocks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.miningGimmick = Objects.requireNonNull(miningGimmick, "miningGimmick");
        this.placedBlocks = Objects.requireNonNull(placedBlocks, "placedBlocks");
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
        // PercentStatNormalize.RATE_KEYS already coerces this to a [0,1] fraction at aggregation time
        // (e.g. 20 -> 0.2). Compare the fraction directly against the roll (same idiom as CritResolver /
        // BreedingBonusListener#BREEDING_EXTRA_CHILD_CHANCE) instead of routing it through a
        // percentRoll-style helper that expects a 0-100 scale, which would silently divide it by 100 again.
        double respawnChanceFraction = aggregator.aggregate(player).totalOf(SUSPICIOUS_RESPAWN_CHANCE_KEY);
        if (!Double.isFinite(respawnChanceFraction) || respawnChanceFraction <= 0.0) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= Math.min(1.0, respawnChanceFraction)) {
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
        Block block = event.getBlock();
        if (placedBlocks.isPlaced(block)) {
            // 設置済みスポナーでも「回収」自体は通す(1個→設置→再回収も1個のままで複製にならない)。
            // 抑止するのはバニラEXPだけ — こちらは設置/破壊を繰り返すと無限に稼げるため。
            // 2026-08-01 実サーバ報告の修正: 以前はここで早期 return しており、setDropItems(false) も
            // 走らないためバニラ挙動(スポナーは何も落とさない)が適用され、プレイヤーの持ち物が
            // 無言で消えていた(「再設置すると破壊で消滅する」の正体)。
            event.setExpToDrop(0);
        }
        if (!dedicatedEffects.isActive(player, EFFECT_SPAWNER_HARVEST)) {
            return;
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!hasSilkTouch(tool)) {
            return;
        }
        if (!(block.getState() instanceof CreatureSpawner source)) {
            return;
        }
        if (isEmptySpawner(source)) {
            // 中身が解決できないスポナーは「空のスポナーを落とす」のではなく回収を見送る
            // (=バニラ挙動へ戻す)。空の実物を握らせるより、落ちない方が原因を追いやすい。
            plugin.getLogger().warning("[mining] スポナー回収を見送りました: "
                    + "spawned-type も spawn-potentials も解決できません at " + block.getLocation());
            return;
        }
        ItemStack drop = spawnerItem(source);
        if (drop == null) {
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        block.getWorld().dropItemNaturally(block.getLocation(), drop);
    }

    /**
     * 回収してもプレイヤーに何も渡らない「空スポナー」かどうか。
     *
     * <p>Paper 1.21.11 の {@link CreatureSpawner#getSpawnedType()} は {@code @Nullable} で、
     * 実装は {@code nextSpawnData} が無いときと NBT のエンティティ型が解決できないときに
     * {@code null} を返す。ただし {@code spawnPotentials} 側にだけ中身があるスポナーは実在するので、
     * 型が解決できなくても potentials が空でなければ「中身あり」と扱う(状態を丸写しするため失われない)。
     */
    private static boolean isEmptySpawner(CreatureSpawner source) {
        EntityType spawnedType = source.getSpawnedType();
        if (spawnedType != null && spawnedType != EntityType.UNKNOWN) {
            return false;
        }
        return source.getPotentialSpawns().isEmpty();
    }

    /**
     * Creates the item-state copy required by Paper/Bukkit: {@code getBlockState()} returns a copy,
     * so both {@code setBlockState()} and {@code setItemMeta()} are required to retain the state.
     *
     * <p><b>2026-08-01 「回収したスポナーの中身が空」の修正</b>: 以前は
     * {@code itemSpawner.setSpawnedType(source.getSpawnedType())} と entity type だけを写していた。
     * これには2つの穴があった。
     * <ol>
     *   <li>{@code getSpawnedType()} が {@code null} を返すと、Paper の
     *       {@code setSpawnedType(null)} は<b>「spawnPotentials を空にして空の SpawnData を入れる」</b>
     *       という明示的な空スポナー化を行う。つまり<b>無言で空のスポナーを作って落としていた</b>。</li>
     *   <li>成功した場合でも {@code spawnPotentials}/遅延/湧き範囲/湧き数といった元スポナーの設定が
     *       すべて捨てられていた。</li>
     * </ol>
     * ブロック状態を丸ごと写せば両方とも起きない。
     */
    private static ItemStack spawnerItem(CreatureSpawner source) {
        ItemStack item = new ItemStack(Material.SPAWNER, 1);
        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) {
            return null;
        }
        meta.setBlockState(source);
        item.setItemMeta(meta);
        return item;
    }

    private boolean hasSilkTouch(ItemStack tool) {
        if (tool == null || !tool.hasItemMeta() || silkTouch == null) {
            return false;
        }
        ItemMeta meta = tool.getItemMeta();
        return meta.getEnchantLevel(silkTouch) > 0;
    }
}
