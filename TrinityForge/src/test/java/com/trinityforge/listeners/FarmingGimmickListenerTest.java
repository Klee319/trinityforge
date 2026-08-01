package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FarmingGimmickListener}(2026-08-01 新設): 対象判定
 * ({@code farming_progression.yml} の {@code block_drops} 表と同じ分類ロジック、
 * {@link NativeSkillExperienceListener#gatheringExp} を流用) + プレイヤー設置ブロック除外
 * + drop-table 抽選。{@link DiggingGimmickListenerTest} と同型。
 */
class FarmingGimmickListenerTest {

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private FarmingGimmickConfig gimmickConfig;
    private CrossPluginItemResolver itemResolver;
    private PlacedBlockTracker placedBlockTracker;
    private PlayerMock player;

    /** WHEAT は block_drops に載っており、そのドロップ(WHEAT)も同じ表に載っている。 */
    private static final SkillCatalogEntry FARMING_ENTRY = new SkillCatalogEntry(
            "FARMING", 100, "1", level -> 1L,
            Map.of("block_drops.WHEAT", 6.0),
            Map.of());

    private static final DropTableConfig.Category ALWAYS = new DropTableConfig.Category(
            "gacha_tier1", "ガチャ券(初級)", 100.0,
            List.of(new DropTableConfig.Entry("tf_gacha_ticket_1", 1, 1)), false);

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(FarmingGimmickConfig.class);
        itemResolver = mock(CrossPluginItemResolver.class);
        // 設置マークは mock で直接制御する。実 PlacedBlockTracker はチャンクPDCへ書くので、
        // ブロックを mock している以上マークが乗らず「設置扱い」を再現できない
        // (= 素の isPlaced ガードへ退行させてもテストが赤くならず、守りにならない)。
        placedBlockTracker = mock(PlacedBlockTracker.class);
        when(dedicatedEffects.dropGatePerks()).thenReturn(Map.of());
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private FarmingGimmickListener listener() {
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.FARMING)).thenReturn(FARMING_ENTRY);
        return new FarmingGimmickListener(
                dedicatedEffects, gimmickConfig, catalog, placedBlockTracker, itemResolver);
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

    @Test
    @DisplayName("drop-tables が空なら何もしない(農業に足す前と完全に同じ挙動)")
    void ignoresBreakWhenDropTablesEmpty() {
        when(gimmickConfig.dropTables()).thenReturn(Map.of());
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.WHEAT);

        listener().onBlockBreak(breakEvent(block));

        org.mockito.Mockito.verifyNoInteractions(itemResolver);
    }

    @Test
    @DisplayName("block_drops 表に無いブロックでは抽選しない(石を掘っても農業の景品は出ない)")
    void ignoresBlockNotInFarmingBlockDropsTable() {
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha_tier1", ALWAYS));
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.STONE);

        listener().onBlockBreak(breakEvent(block));

        org.mockito.Mockito.verifyNoInteractions(itemResolver);
    }

    /**
     * <b>この機能が実際に届くかどうかを決めている一本</b>。作物は必ず植える = 種の設置が
     * {@code BlockPlaceEvent} を通るので {@link PlacedBlockTracker} に必ずマークが付く。
     * 掘削と同じ {@code isPlaced} ガードを素で掛けると<b>自分の畑での収穫が全部除外され、
     * 農業のドロップテーブルはどの畑でも一度も発動しない</b>(= 設定だけ足して誰にも届かない)。
     */
    @Test
    @DisplayName("自分で植えた完熟作物は【設置マークがあっても】抽選する(畑での収穫が本来の対象)")
    void playerPlantedMatureCropStillRolls() {
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha_tier1", ALWAYS));
        when(itemResolver.create(eq("tf_gacha_ticket_1")))
                .thenReturn(Optional.of(new ItemStack(Material.PAPER)));
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_HOE));

        Block block = harvestableBlock(Material.WHEAT, List.of(new ItemStack(Material.WHEAT)));
        when(placedBlockTracker.isPlaced(block)).thenReturn(true); // 種を植えた = 必ずマークが付く
        int before = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        listener().onBlockBreak(breakEvent(block));

        assertEquals(1, player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size() - before,
                "植えた作物を isPlaced で弾くと農業のドロップテーブルは実質どこでも発動しない");
    }

    @Test
    @DisplayName("未成熟の作物では抽選しない(種を植えて即壊すループを塞ぐのはこちら)")
    void immatureCropDoesNotRoll() {
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha_tier1", ALWAYS));
        when(itemResolver.create(eq("tf_gacha_ticket_1")))
                .thenReturn(Optional.of(new ItemStack(Material.PAPER)));
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_HOE));

        Block block = harvestableBlock(Material.WHEAT, List.of(new ItemStack(Material.WHEAT)));
        org.bukkit.block.data.Ageable immature = mock(org.bukkit.block.data.Ageable.class);
        when(immature.getAge()).thenReturn(0);
        when(immature.getMaximumAge()).thenReturn(7);
        when(block.getBlockData()).thenReturn(immature);

        listener().onBlockBreak(breakEvent(block));

        org.mockito.Mockito.verifyNoInteractions(itemResolver);
    }

    /**
     * 成熟の概念を持たない植物(サトウキビ/竹/サボテン/コンブ)は「設置しても壊せば手元に戻る」ので
     * 置く→壊すが無コストで回る。ここだけは掘削と同じ {@code isPlaced} ガードで塞ぐ。
     */
    @Test
    @DisplayName("成熟ガード対象外のブロックは設置マークで除外される(置く→壊すの無限ループ対策)")
    void placedNonCropBlockIsExcluded() {
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha_tier1", ALWAYS));
        when(itemResolver.create(eq("tf_gacha_ticket_1")))
                .thenReturn(Optional.of(new ItemStack(Material.PAPER)));
        Block block = harvestableBlock(Material.SUGAR_CANE, List.of(new ItemStack(Material.SUGAR_CANE)));
        when(placedBlockTracker.isPlaced(block)).thenReturn(true);

        listener().onBlockBreak(breakEvent(block));

        org.mockito.Mockito.verifyNoInteractions(itemResolver);
    }

    @Test
    @DisplayName("収穫対象ブロックでは drop-table を引いて景品を落とす")
    void farmingTargetBlockRollsDropTableAndDrops() {
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha_tier1", ALWAYS));
        when(itemResolver.create(eq("tf_gacha_ticket_1")))
                .thenReturn(Optional.of(new ItemStack(Material.PAPER)));
        PlayerInventory inv = player.getInventory();
        inv.setItemInMainHand(new ItemStack(Material.DIAMOND_HOE));

        Block block = harvestableBlock(Material.WHEAT, List.of(new ItemStack(Material.WHEAT)));
        int before = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        listener().onBlockBreak(breakEvent(block));

        int after = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(1, after - before,
                "trigger-chance-percent=100 なら唯一の開いたエントリを必ず引いて落とす");
    }

    @Test
    @DisplayName("解決できないアイテムidでもブロック破壊ハンドラから例外を投げない")
    void unresolvableItemIdIsSwallowed() {
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha_tier1", ALWAYS));
        when(itemResolver.create(any())).thenReturn(Optional.empty());
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_HOE));

        Block block = harvestableBlock(Material.WHEAT, List.of(new ItemStack(Material.WHEAT)));
        int before = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        listener().onBlockBreak(breakEvent(block));

        assertEquals(before, player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size());
    }
}
