package com.trinityforge.listeners;

import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.config.domains.MiningGimmickConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-07-26 魔法(Ars)破壊とTF採取ギミックの分離: {@link SpellBreakGuard#METADATA_KEY} が立った
 * {@link BlockBreakEvent} では、採取系ギミックが一切発動してはならない(=フォークの合成イベント経由の
 * 破壊にTFの採取恩恵を与えない)。マーカーが無い通常のプレイヤー破壊では従来どおり発動することも
 * 併せて確認する(遮断しすぎていないことの回帰防止)。
 *
 * <p>CRITICAL: {@link FarmingHarvestListener} の auto-replant/area-harvest は、この防護が無いと
 * フォーク側の再ドロップとTFのage0再設置が重なって「二重ドロップ+自動再植+範囲収穫」の永久機関になる
 * (2026-07-26 spellbreak-drop-gate-fix 調査)。
 */
class SpellBreakGuardRegressionTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void markAsSpellBreak(Block block) {
        block.setMetadata(SpellBreakGuard.METADATA_KEY, new FixedMetadataValue(plugin, true));
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
        when(event.isCancelled()).thenReturn(false);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    // ============================================================
    // SpellBreakGuard 単体
    // ============================================================

    @Test
    void isSpellBreakFalseWithoutMarker() {
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        assertEquals(false, SpellBreakGuard.isSpellBreak(block));
    }

    @Test
    void isSpellBreakTrueWithMarker() {
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        markAsSpellBreak(block);
        assertEquals(true, SpellBreakGuard.isSpellBreak(block));
    }

    // ============================================================
    // FarmingHarvestListener (CRITICAL: 自動再植+範囲収穫の永久機関防止)
    // ============================================================

    @Test
    void farmingHarvestSkipsAutoReplantAndAreaHarvestWhenSpellBreakMarked() {
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        FarmingGimmickConfig gimmickConfig = mock(FarmingGimmickConfig.class);
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.areaHarvestRadius(1)).thenReturn(1);
        // auto-replant/area-harvest はどちらもプレイヤートグル既定ON(明示設定不要)。

        Block origin = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);
        markAsSpellBreak(origin);

        BlockBreakEvent event = breakEvent(origin);
        FarmingHarvestListener listener = new FarmingHarvestListener(
                plugin, dedicatedEffects, gimmickConfig, new FeedbackLayer());
        listener.onBlockBreak(event);

        verify(event, never()).setDropItems(false);
        assertEquals(Material.WHEAT, neighbor.getType(),
                "spell-break marker must suppress area-harvest of the neighbor");
    }

    @Test
    void farmingHarvestStillReplantsAndAreaHarvestsWithoutMarker() {
        // 遮断しすぎていないことの確認: マーカー無しの通常破壊では従来どおり発動する。
        // 2026-07-27: 範囲収穫にツール判定が入ったので鍬を持たせる(素のバニラの鍬で通る)。
        player.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.DIAMOND_HOE));
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        FarmingGimmickConfig gimmickConfig = mock(FarmingGimmickConfig.class);
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.areaHarvestRadius(1)).thenReturn(1);

        Block origin = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);

        BlockBreakEvent event = breakEvent(origin);
        FarmingHarvestListener listener = new FarmingHarvestListener(
                plugin, dedicatedEffects, gimmickConfig, new FeedbackLayer());
        listener.onBlockBreak(event);

        verify(event).setDropItems(false);
        assertEquals(Material.AIR, neighbor.getType(),
                "an unmarked (ordinary) break must still area-harvest the neighbor");
    }

    // ============================================================
    // VeinMiningListener (一括破壊)
    // ============================================================

    @Test
    void veinMiningSkipsChainBreakWhenSpellBreakMarked() {
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        MiningGimmickConfig gimmickConfig = mock(MiningGimmickConfig.class);
        CrossPluginItemResolver itemResolver = mock(CrossPluginItemResolver.class);
        PlacedBlockTracker placedBlockTracker = new PlacedBlockTracker(plugin);
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        when(dedicatedEffects.valueMax(any(), eq("vein-mining"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.veinMiningMaxExtraBlocks(1)).thenReturn(8);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.DIAMOND_ORE);
        neighbor.setType(Material.DIAMOND_ORE);
        markAsSpellBreak(origin);

        VeinMiningListener listener = new VeinMiningListener(
                dedicatedEffects, gimmickConfig, itemResolver, placedBlockTracker, new FeedbackLayer());
        listener.onBlockBreak(breakEvent(origin));

        assertEquals(Material.DIAMOND_ORE, neighbor.getType(),
                "spell-break marker must suppress vein-mining chain-break");
    }

    @Test
    void veinMiningStillChainBreaksWithoutMarker() {
        // 2026-07-27: 一括破壊にツール判定が入ったのでツルハシを持たせる(素のバニラのもので通る)。
        player.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.DIAMOND_PICKAXE));
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        MiningGimmickConfig gimmickConfig = mock(MiningGimmickConfig.class);
        CrossPluginItemResolver itemResolver = mock(CrossPluginItemResolver.class);
        PlacedBlockTracker placedBlockTracker = new PlacedBlockTracker(plugin);
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        when(dedicatedEffects.valueMax(any(), eq("vein-mining"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.veinMiningMaxExtraBlocks(1)).thenReturn(8);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.DIAMOND_ORE);
        neighbor.setType(Material.DIAMOND_ORE);

        VeinMiningListener listener = new VeinMiningListener(
                dedicatedEffects, gimmickConfig, itemResolver, placedBlockTracker, new FeedbackLayer());
        listener.onBlockBreak(breakEvent(origin));

        assertEquals(Material.AIR, neighbor.getType(),
                "an unmarked (ordinary) break must still chain-break the neighbor");
    }

    @Test
    void veinMiningDropTablesSkippedWhenSpellBreakMarked() {
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        MiningGimmickConfig gimmickConfig = mock(MiningGimmickConfig.class);
        CrossPluginItemResolver itemResolver = mock(CrossPluginItemResolver.class);
        PlacedBlockTracker placedBlockTracker = new PlacedBlockTracker(plugin);
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        DropTableConfig.Category category = new DropTableConfig.Category("gacha", "Gacha", 100.0,
                List.of(new DropTableConfig.Entry("tf_gacha_ticket_1", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha", category));
        when(dedicatedEffects.dropGatePerks()).thenReturn(Map.of());

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.DIAMOND_ORE);
        markAsSpellBreak(block);

        VeinMiningListener listener = new VeinMiningListener(
                dedicatedEffects, gimmickConfig, itemResolver, placedBlockTracker, new FeedbackLayer());
        listener.onBlockBreakDropTables(breakEvent(block));

        org.mockito.Mockito.verifyNoInteractions(itemResolver);
    }
}
