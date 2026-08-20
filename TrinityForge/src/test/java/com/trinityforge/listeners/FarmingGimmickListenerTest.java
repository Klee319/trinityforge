package com.trinityforge.listeners;

import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FarmingGimmickListener}: 対象判定
 * ({@code farming_progression.yml} の {@code block_drops} 表と同じ分類ロジック、
 * {@link NativeSkillExperienceListener#gatheringExp} を流用) + プレイヤー設置ブロック除外。
 * {@link DiggingGimmickListenerTest} と同型。drop-table 抽選は 2026-08-09 に機構ごと撤去済み。
 */
class FarmingGimmickListenerTest {

    private ServerMock server;
    private FarmingGimmickConfig gimmickConfig;
    private PlacedBlockTracker placedBlockTracker;
    private PlayerMock player;

    /** WHEAT は block_drops に載っており、そのドロップ(WHEAT)も同じ表に載っている。 */
    private static final SkillCatalogEntry FARMING_ENTRY = new SkillCatalogEntry(
            "FARMING", 100, "1", level -> 1L,
            Map.of("block_drops.WHEAT", 6.0),
            Map.of());

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        gimmickConfig = mock(FarmingGimmickConfig.class);
        // 設置マークは mock で直接制御する。実 PlacedBlockTracker はチャンクPDCへ書くので、
        // ブロックを mock している以上マークが乗らず「設置扱い」を再現できない
        // (= 素の isPlaced ガードへ退行させてもテストが赤くならず、守りにならない)。
        placedBlockTracker = mock(PlacedBlockTracker.class);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private FarmingGimmickListener listener() {
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.FARMING)).thenReturn(FARMING_ENTRY);
        return new FarmingGimmickListener(gimmickConfig, catalog, placedBlockTracker);
    }

    private BlockBreakEvent breakEvent(Block block) {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    /**
     * MockBukkit の実ワールド {@code getDrops()} はヘッドレスだと安定しないので、
     * DiggingGimmickListenerTest と同じくブロックを丸ごと mock して分類を決定的にする。
     */
    private Block harvestableBlock(Material type, List<ItemStack> drops) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(type);
        // 成熟ガード(CropMaturity)は BlockData を読む。WHEAT は成熟ガード対象なので、
        // 完熟(age == maximumAge)の BlockData を返さないと未成熟扱いで弾かれる。
        org.bukkit.block.data.Ageable ageable = mock(org.bukkit.block.data.Ageable.class);
        when(ageable.getAge()).thenReturn(7);
        when(ageable.getMaximumAge()).thenReturn(7);
        when(block.getBlockData()).thenReturn(ageable);
        when(block.getLocation()).thenReturn(new org.bukkit.Location(player.getWorld(), 0, 64, 0));
        when(block.getWorld()).thenReturn(player.getWorld());
        when(block.getDrops(any(), any())).thenReturn(drops);
        org.bukkit.Chunk chunk = mock(org.bukkit.Chunk.class);
        when(block.getChunk()).thenReturn(chunk);
        org.bukkit.persistence.PersistentDataContainer chunkPdc =
                mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(chunk.getPersistentDataContainer()).thenReturn(chunkPdc);
        when(chunkPdc.getOrDefault(any(), eq(org.bukkit.persistence.PersistentDataType.LONG_ARRAY), any()))
                .thenReturn(new long[0]);
        return block;
    }

    // 2026-08-09: drop-tables 機構を撤去したので、このリスナーはもう何もドロップしない
    // (対象判定 = anti-loop guard を通す/通さないだけの no-op)。以下は「例外を投げず
    // 静かに no-op すること」の回帰ガードとして残す。

    @Test
    @DisplayName("block_drops 表に無いブロックでも例外を投げない(石を掘っても何も起きない)")
    void ignoresBlockNotInFarmingBlockDropsTable() {
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.STONE);

        listener().onBlockBreak(breakEvent(block));

        int after = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(0, after, "drop-tables 撤去後は何もドロップしない");
    }

    @Test
    @DisplayName("自分で植えた完熟作物を壊しても【設置マークがあっても】例外を投げない")
    void playerPlantedMatureCropDoesNotThrow() {
        Block block = harvestableBlock(Material.WHEAT, List.of(new ItemStack(Material.WHEAT)));
        when(placedBlockTracker.isPlaced(block)).thenReturn(true); // 種を植えた = 必ずマークが付く
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_HOE));
        int before = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        listener().onBlockBreak(breakEvent(block));

        assertEquals(before, player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size());
    }

    @Test
    @DisplayName("未成熟の作物を壊しても例外を投げない")
    void immatureCropDoesNotThrow() {
        Block block = harvestableBlock(Material.WHEAT, List.of(new ItemStack(Material.WHEAT)));
        org.bukkit.block.data.Ageable immature = mock(org.bukkit.block.data.Ageable.class);
        when(immature.getAge()).thenReturn(0);
        when(immature.getMaximumAge()).thenReturn(7);
        when(block.getBlockData()).thenReturn(immature);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_HOE));

        listener().onBlockBreak(breakEvent(block));

        int after = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(0, after);
    }

    @Test
    @DisplayName("成熟ガード対象外の設置ブロック(サトウキビ等)を壊しても例外を投げない")
    void placedNonCropBlockDoesNotThrow() {
        Block block = harvestableBlock(Material.SUGAR_CANE, List.of(new ItemStack(Material.SUGAR_CANE)));
        when(placedBlockTracker.isPlaced(block)).thenReturn(true);

        listener().onBlockBreak(breakEvent(block));

        int after = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(0, after);
    }
}
