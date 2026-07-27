package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.MiningGimmickConfig;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BrushableBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.loot.LootTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code suspicious-block-respawn} → {@code suspicious_respawn_chance} (2026-07-23 stat-gate-overhaul §2
 * 移行B10: 装備+perk合算。100%以上のstat値は {@link com.trinityforge.mining.MiningGimmickPolicy#percentRoll}
 * が乱数に関わらず必ず成立させるため、決定的にテストできる).
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
}
