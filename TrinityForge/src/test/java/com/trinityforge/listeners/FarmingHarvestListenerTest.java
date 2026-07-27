package com.trinityforge.listeners;

import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FarmingHarvestListener#onBlockBreak}: 2026-07-25 gather-rework-active-framework §1(area-harvest
 * SCALE化・tier解決)/§2 B-2(プレイヤートグルOFFで無効化)の回帰確認。
 */
class FarmingHarvestListenerTest {

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private FarmingGimmickConfig gimmickConfig;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(FarmingGimmickConfig.class);
        player = server.addPlayer();
        // 2026-07-27: 範囲収穫にツール判定(GatheringToolMatcher)が入ったため、素手のままだと
        // 発動しない。素のバニラの鍬はマテリアル推論で FARMING として通る。
        // (auto-replant 側は意図的にツール判定なしなので、素手でも従来どおり動く)
        player.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.DIAMOND_HOE));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private FarmingHarvestListener listener() {
        return new FarmingHarvestListener(MockBukkit.createMockPlugin(), dedicatedEffects, gimmickConfig,
                new FeedbackLayer());
    }

    private static Block matureWheat(PlayerMock player, int x, int z) {
        Block block = player.getWorld().getBlockAt(x, 64, z);
        block.setType(Material.WHEAT);
        Ageable ageable = (Ageable) block.getBlockData();
        ageable.setAge(ageable.getMaximumAge());
        block.setBlockData(ageable);
        return block;
    }

    private BlockBreakEvent breakEvent(Block block) {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @Test
    void areaHarvestToggleOffLeavesNeighborUnharvestedEvenWhenUnlocked() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(false);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.areaHarvestRadius(1)).thenReturn(1);
        PlayerData.of(player).setAreaHarvestEnabled(false);

        Block origin = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.WHEAT, neighbor.getType(), "toggle OFF must not area-harvest the neighbor");
    }

    @Test
    void areaHarvestHarvestsNeighborWhenToggleOnAndUnlocked() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(false);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.areaHarvestRadius(1)).thenReturn(1);
        // areaHarvestEnabled defaults to true; no explicit set needed.

        Block origin = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.AIR, neighbor.getType(), "toggle ON + unlocked must area-harvest the neighbor");
    }

    @Test
    void autoReplantToggleOffLeavesVanillaDropsUncancelledEvenWhenUnlocked() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.empty());
        PlayerData.of(player).setAutoReplantEnabled(false);

        Block origin = matureWheat(player, 0, 0);
        BlockBreakEvent event = breakEvent(origin);

        listener().onBlockBreak(event);

        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setDropItems(false);
    }
}
