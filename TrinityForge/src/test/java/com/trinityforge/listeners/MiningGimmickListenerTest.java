package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.MiningGimmickConfig;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.loot.LootTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code suspicious-block-respawn} → {@code suspicious_respawn_chance} (2026-07-23 stat-gate-overhaul §2
 * 移行B10: 装備+perk合算。100%以上のstat値は{@link MiningGimmickListener}が乱数に関わらず必ず成立させる
 * ({@code Math.min(1.0, fraction)}でクランプするため)ので、決定的にテストできる).
 *
 * <p>2026-07-27: 確定バグの回帰テストを追加(旧実装は{@code totalOf}が返す既にフラクション化済みの値
 * ({@code PercentStatNormalize.RATE_KEYS}参照)を、さらに{@code MiningGimmickPolicy.percentRoll}
 * (0-100スケール前提)で100分割しており、実効確率が設定値の100分の1になっていた)。
 */
class MiningGimmickListenerTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static PlayerStatAggregator aggregatorReturning(Player player, double respawnChance) {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(new PlayerCombatAggregate(
                Map.of("suspicious_respawn_chance", respawnChance), Map.of(), Map.of(), Map.of(), Map.of()));
        return aggregator;
    }

    /** The at-break block (SUSPICIOUS_SAND) plus the AIR "current" block the 1-tick respawn re-reads. */
    private record BreakFixture(BlockBreakEvent event, Block current) {
    }

    private BreakFixture suspiciousSandBreakEvent(Player player) {
        org.bukkit.Location location = mock(org.bukkit.Location.class);
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.SUSPICIOUS_SAND);
        when(block.getLocation()).thenReturn(location);
        World world = mock(World.class);
        when(block.getWorld()).thenReturn(world);
        Block current = mock(Block.class);
        when(current.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(location)).thenReturn(current);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        return new BreakFixture(event, current);
    }

    @Test
    void newSuspiciousRespawnChanceStatSchedulesRespawn() {
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 1000.0); // >=100% → 常に成立
        MiningGimmickListener listener =
                new MiningGimmickListener(MockBukkit.createMockPlugin(), dedicatedEffects, aggregator);
        BreakFixture fixture = suspiciousSandBreakEvent(player);

        listener.onBlockBreak(fixture.event());
        server.getScheduler().performTicks(2);

        verify(fixture.current(), times(1)).setType(Material.SUSPICIOUS_SAND);
    }

    @Test
    void respawnedSuspiciousBlockCarriesNonNullLootTable() {
        // GTH-04: a block respawned via Block#setType has no loot table by default, so brushing it to
        // completion silently drops nothing. The respawn must explicitly attach a real loot table.
        // org.bukkit.loot.LootTables#getLootTable() delegates to the static Bukkit.getLootTable(key),
        // which MockBukkit's ServerMock does not implement (UnimplementedOperationException) — stub the
        // static call so the listener's own logic (not MockBukkit's loot-table plumbing) is what's tested.
        try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkitStatic =
                     org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            LootTable fakeLootTable = mock(LootTable.class);
            bukkitStatic.when(() -> org.bukkit.Bukkit.getLootTable(any(org.bukkit.NamespacedKey.class)))
                    .thenReturn(fakeLootTable);

            Player player = mock(Player.class);
            DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
            PlayerStatAggregator aggregator = aggregatorReturning(player, 1000.0); // >=100% → 常に成立
            MiningGimmickConfig miningGimmick = new MiningGimmickConfig(); // 既定値のまま(load()未呼び出し)
            MiningGimmickListener listener = new MiningGimmickListener(
                    MockBukkit.createMockPlugin(), dedicatedEffects, aggregator, miningGimmick);
            BreakFixture fixture = suspiciousSandBreakEvent(player);
            BrushableBlock brushableState = mock(BrushableBlock.class);
            when(fixture.current().getState()).thenReturn(brushableState);

            listener.onBlockBreak(fixture.event());
            server.getScheduler().performTicks(2);

            verify(brushableState, times(1)).setLootTable(same(fakeLootTable), anyLong());
            verify(brushableState, times(1)).update(true);
        }
    }

    @Test
    void fractionPointTwoRespawnChanceHitsAtRollPointOne() {
        // Regression proof for the confirmed double-scaling bug: totalOf() already returns a fraction
        // (0.2 == the "20%" a config author wrote) because suspicious_respawn_chance is coerced by
        // PercentStatNormalize.RATE_KEYS before aggregation. Under the OLD implementation
        // (MiningGimmickPolicy.percentRoll(0.2, 0.1)) this would divide 0.2 by 100 again -> effective
        // chance 0.2% -> roll 0.1 would MISS. The fixed listener compares the fraction directly, so
        // roll 0.1 < 0.2 must HIT.
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 0.2);
        MiningGimmickListener listener =
                new MiningGimmickListener(MockBukkit.createMockPlugin(), dedicatedEffects, aggregator);
        BreakFixture fixture = suspiciousSandBreakEvent(player);

        try (MockedStatic<ThreadLocalRandom> rngStatic = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom rng = mock(ThreadLocalRandom.class);
            rngStatic.when(ThreadLocalRandom::current).thenReturn(rng);
            when(rng.nextDouble()).thenReturn(0.1);

            listener.onBlockBreak(fixture.event());
            server.getScheduler().performTicks(2);
        }

        verify(fixture.current(), times(1)).setType(Material.SUSPICIOUS_SAND);
    }

    @Test
    void fractionPointTwoRespawnChanceMissesAtRollPointThree() {
        // Same fraction (0.2 == 20%) but roll 0.3 sits above the threshold -> must MISS under the fixed
        // implementation (0.3 >= 0.2), same as it always would have under both old and new code (this
        // half alone would not distinguish the bug -- see fractionPointTwoRespawnChanceHitsAtRollPointOne
        // for the roll that actually proves the fix).
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 0.2);
        MiningGimmickListener listener =
                new MiningGimmickListener(MockBukkit.createMockPlugin(), dedicatedEffects, aggregator);
        BreakFixture fixture = suspiciousSandBreakEvent(player);

        try (MockedStatic<ThreadLocalRandom> rngStatic = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom rng = mock(ThreadLocalRandom.class);
            rngStatic.when(ThreadLocalRandom::current).thenReturn(rng);
            when(rng.nextDouble()).thenReturn(0.3);

            listener.onBlockBreak(fixture.event());
            server.getScheduler().performTicks(2);
        }

        verify(fixture.current(), never()).setType(any());
    }

    @Test
    void zeroNegativeOrNonFiniteRespawnChanceNeverSchedulesAndNeverThrows() {
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        MiningGimmickListener listener = new MiningGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, aggregatorReturning(player, 0.0));
        BreakFixture zeroFixture = suspiciousSandBreakEvent(player);
        listener.onBlockBreak(zeroFixture.event());

        MiningGimmickListener negativeListener = new MiningGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, aggregatorReturning(player, -5.0));
        BreakFixture negativeFixture = suspiciousSandBreakEvent(player);
        negativeListener.onBlockBreak(negativeFixture.event());

        MiningGimmickListener nanListener = new MiningGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, aggregatorReturning(player, Double.NaN));
        BreakFixture nanFixture = suspiciousSandBreakEvent(player);
        nanListener.onBlockBreak(nanFixture.event());

        server.getScheduler().performTicks(2);

        verify(zeroFixture.current(), never()).setType(any());
        verify(negativeFixture.current(), never()).setType(any());
        verify(nanFixture.current(), never()).setType(any());
    }

    @Test
    void oldDedicatedEffectValueNoLongerSchedulesRespawn() {
        // Regression guard: the retired dedicated-effect id "suspicious-block-respawn" must not respawn
        // the block anymore — only the new stat key does.
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.valueSum(player, "suspicious-block-respawn")).thenReturn(1000.0);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 0.0);
        MiningGimmickListener listener =
                new MiningGimmickListener(MockBukkit.createMockPlugin(), dedicatedEffects, aggregator);
        BreakFixture fixture = suspiciousSandBreakEvent(player);

        listener.onBlockBreak(fixture.event());
        server.getScheduler().performTicks(2);

        verify(fixture.current(), never()).setType(any());
    }

    @Test
    void harvestedSpawnerItemPreservesSpawnedEntityTypeAndSuppressesVanillaExp() {
        Player player = server.addPlayer();
        ItemStack tool = new ItemStack(Material.DIAMOND_PICKAXE);
        Enchantment silkTouch = org.bukkit.Registry.ENCHANTMENT.get(
                org.bukkit.NamespacedKey.minecraft("silk_touch"));
        tool.addUnsafeEnchantment(silkTouch, 1);
        player.getInventory().setItemInMainHand(tool);

        Block block = player.getWorld().getBlockAt(8, 64, 8);
        block.setType(Material.SPAWNER);
        CreatureSpawner source = assertInstanceOf(CreatureSpawner.class, block.getState());
        source.setSpawnedType(EntityType.ZOMBIE);
        source.update(true);

        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.isActive(player, "spawner-silktouch-harvest")).thenReturn(true);
        PlacedBlockTracker placedBlocks = new PlacedBlockTracker(MockBukkit.createMockPlugin());
        MiningGimmickListener listener = new MiningGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, mock(PlayerStatAggregator.class),
                new MiningGimmickConfig(), placedBlocks);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);

        listener.onBlockBreak(event);

        Item dropped = player.getWorld().getEntitiesByClass(Item.class).iterator().next();
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, dropped.getItemStack().getItemMeta());
        CreatureSpawner droppedState = assertInstanceOf(CreatureSpawner.class, meta.getBlockState());
        assertEquals(EntityType.ZOMBIE, droppedState.getSpawnedType());
        verify(event).setDropItems(false);
        verify(event).setExpToDrop(0);
    }

    @Test
    void playerPlacedSpawnerCannotBeHarvestedAgainAndDropsNoSpawnerExp() {
        Player player = server.addPlayer();
        ItemStack tool = new ItemStack(Material.DIAMOND_PICKAXE);
        Enchantment silkTouch = org.bukkit.Registry.ENCHANTMENT.get(
                org.bukkit.NamespacedKey.minecraft("silk_touch"));
        tool.addUnsafeEnchantment(silkTouch, 1);
        player.getInventory().setItemInMainHand(tool);

        Block block = player.getWorld().getBlockAt(8, 64, 8);
        block.setType(Material.SPAWNER);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.isActive(player, "spawner-silktouch-harvest")).thenReturn(true);
        PlacedBlockTracker placedBlocks = new PlacedBlockTracker(MockBukkit.createMockPlugin());
        placedBlocks.markPlaced(block);
        MiningGimmickListener listener = new MiningGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, mock(PlayerStatAggregator.class),
                new MiningGimmickConfig(), placedBlocks);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);

        listener.onBlockBreak(event);

        assertEquals(0, player.getWorld().getEntitiesByClass(Item.class).size());
        verify(event, never()).setDropItems(false);
        verify(event).setExpToDrop(0);
    }
}
