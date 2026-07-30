package com.trinityforge.listeners;

import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.gathering.ChainBreakExpGrant;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Item;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void rightClickMatureCropHarvestsReplantsAndSuppressesNativeInteractExperience() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.empty());
        ChainBreakExpGrant expGrant = mock(ChainBreakExpGrant.class);
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(
                new FarmingHarvestListener(plugin, dedicatedEffects, gimmickConfig,
                        new FeedbackLayer(), expGrant),
                plugin);
        IgnoredCancelledMonitor nativeInteractProbe = new IgnoredCancelledMonitor();
        server.getPluginManager().registerEvents(nativeInteractProbe, plugin);
        BreakPathProbe breakPathProbe = new BreakPathProbe();
        server.getPluginManager().registerEvents(breakPathProbe, plugin);

        Block crop = matureWheat(player, 0, 0);
        ((BlockMock) crop).setDrops(List.of(
                new ItemStack(Material.WHEAT),
                new ItemStack(Material.WHEAT_SEEDS, 2)));
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(),
                crop, BlockFace.UP, EquipmentSlot.HAND);

        server.getPluginManager().callEvent(event);

        assertEquals(Material.WHEAT, crop.getType());
        assertEquals(0, ((Ageable) crop.getBlockData()).getAge(),
                "right-click harvest must immediately replant the crop at age 0");
        assertEquals(1, droppedAmount(Material.WHEAT),
                "right-click harvest must drop the harvested produce");
        assertEquals(1, droppedAmount(Material.WHEAT_SEEDS),
                "replanting must consume exactly one seed from the harvested drops");
        assertTrue(event.isCancelled(),
                "handled interaction must be cancelled so MONITOR ignoreCancelled listeners do not double-grant EXP");
        assertEquals(0, nativeInteractProbe.calls,
                "NativeSkillExperienceListener's ignoreCancelled right-click path must be suppressed");
        assertEquals(1, breakPathProbe.calls,
                "normal BlockBreakEvent listeners must receive the authorized harvest exactly once");
        verifyNoInteractions(expGrant);
    }

    private int droppedAmount(Material material) {
        return player.getWorld().getEntitiesByClass(Item.class).stream()
                .map(Item::getItemStack)
                .filter(stack -> stack.getType() == material)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }

    @Test
    void rightClickDoesNotHarvestWhenBreakProtectionCancelsSyntheticEvent() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(
                new FarmingHarvestListener(plugin, dedicatedEffects, gimmickConfig,
                        new FeedbackLayer(), mock(ChainBreakExpGrant.class)),
                plugin);
        CancellingBreakProtection protection = new CancellingBreakProtection();
        server.getPluginManager().registerEvents(protection, plugin);

        Block crop = matureWheat(player, 0, 0);
        ((BlockMock) crop).setDrops(List.of(new ItemStack(Material.WHEAT)));
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(),
                crop, BlockFace.UP, EquipmentSlot.HAND);

        server.getPluginManager().callEvent(event);

        Ageable ageable = (Ageable) crop.getBlockData();
        assertEquals(ageable.getMaximumAge(), ageable.getAge(),
                "cancelled break authorization must leave the crop untouched");
        assertEquals(0, droppedAmount(Material.WHEAT),
                "cancelled break authorization must not drop the crop");
        assertEquals(1, protection.calls);
        assertTrue(event.isCancelled(),
                "the intercepted right click must remain cancelled after break authorization is denied");
    }

    @Test
    void rightClickHonorsSyntheticBreakDropSuppressionWhileReplanting() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(
                new FarmingHarvestListener(plugin, dedicatedEffects, gimmickConfig,
                        new FeedbackLayer(), mock(ChainBreakExpGrant.class)),
                plugin);
        SuppressingDropsBreakListener dropSuppressor = new SuppressingDropsBreakListener();
        server.getPluginManager().registerEvents(dropSuppressor, plugin);

        Block crop = matureWheat(player, 0, 0);
        ((BlockMock) crop).setDrops(List.of(new ItemStack(Material.WHEAT)));
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(),
                crop, BlockFace.UP, EquipmentSlot.HAND);

        server.getPluginManager().callEvent(event);

        assertEquals(0, ((Ageable) crop.getBlockData()).getAge(),
                "drop suppression must not prevent the authorized replant");
        assertEquals(0, droppedAmount(Material.WHEAT),
                "setDropItems(false) on the synthetic break must suppress base drops");
        assertEquals(1, dropSuppressor.calls);
        assertTrue(event.isCancelled());
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

    private static final class IgnoredCancelledMonitor implements Listener {
        private int calls;

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onInteract(PlayerInteractEvent event) {
            calls++;
        }
    }

    private static final class BreakPathProbe implements Listener {
        private int calls;

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBlockBreak(BlockBreakEvent event) {
            calls++;
        }
    }

    private static final class CancellingBreakProtection implements Listener {
        private int calls;

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockBreak(BlockBreakEvent event) {
            calls++;
            event.setCancelled(true);
        }
    }

    private static final class SuppressingDropsBreakListener implements Listener {
        private int calls;

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockBreak(BlockBreakEvent event) {
            calls++;
            event.setDropItems(false);
        }
    }
}
