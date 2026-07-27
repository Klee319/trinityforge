package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.DiggingGimmickConfig;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * {@link DiggingGimmickListener}: 対象判定({@code digging_progression.yml}の{@code digging_break}表と
 * 同じ分類ロジック、{@link NativeSkillExperienceListener#gatheringExp}を流用)+ プレイヤー設置ブロック除外
 * ({@link PlacedBlockTracker#isPlaced}、読み取り専用) + drop-table抽選を検証する。
 */
class DiggingGimmickListenerTest {

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private DiggingGimmickConfig gimmickConfig;
    private CrossPluginItemResolver itemResolver;
    private PlacedBlockTracker placedBlockTracker;
    private PlayerMock player;

    /** DIRT is listed in digging_break with a nonzero exp AND its plain drop (DIRT itself) is also listed. */
    private static final SkillCatalogEntry DIGGING_ENTRY = new SkillCatalogEntry(
            "DIGGING", 100, "1", level -> 1L,
            Map.of("digging_break.DIRT", 8.0),
            Map.of());

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(DiggingGimmickConfig.class);
        itemResolver = mock(CrossPluginItemResolver.class);
        placedBlockTracker = new PlacedBlockTracker(MockBukkit.createMockPlugin());
        when(dedicatedEffects.dropGatePerks()).thenReturn(Map.of());
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private DiggingGimmickListener listener(NativeSkillCatalog catalog) {
        return new DiggingGimmickListener(dedicatedEffects, gimmickConfig, catalog, placedBlockTracker, itemResolver);
    }

    private BlockBreakEvent breakEvent(Block block) {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private NativeSkillCatalog mockCatalog() {
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.DIGGING)).thenReturn(DIGGING_ENTRY);
        return catalog;
    }

    @Test
    void ignoresBreakWhenDropTablesEmpty() {
        when(gimmickConfig.dropTables()).thenReturn(Map.of());
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.DIRT);

        listener(mockCatalog()).onBlockBreak(breakEvent(block));

        // No exception, no drop-table interaction attempted — verified indirectly by itemResolver never
        // being asked to resolve anything.
        org.mockito.Mockito.verifyNoInteractions(itemResolver);
    }

    @Test
    void ignoresBlockNotInDiggingBreakTable() {
        DropTableConfig.Category category = new DropTableConfig.Category("ruins_thread", "Ruins", 100.0,
                List.of(new DropTableConfig.Entry("thread_empty", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("ruins_thread", category));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.STONE); // not in DIGGING_ENTRY's digging_break table

        listener(mockCatalog()).onBlockBreak(breakEvent(block));

        org.mockito.Mockito.verifyNoInteractions(itemResolver);
    }

    @Test
    void ignoresPlayerPlacedBlockEvenIfDiggingTarget() {
        DropTableConfig.Category category = new DropTableConfig.Category("ruins_thread", "Ruins", 100.0,
                List.of(new DropTableConfig.Entry("thread_empty", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("ruins_thread", category));
        when(itemResolver.create(eq("thread_empty"))).thenReturn(Optional.of(new ItemStack(Material.PAPER)));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.DIRT);
        placedBlockTracker.markPlaced(block);

        listener(mockCatalog()).onBlockBreak(breakEvent(block));

        org.mockito.Mockito.verifyNoInteractions(itemResolver);
    }

    @Test
    void diggingTargetBlockRollsDropTableAndDrops() {
        DropTableConfig.Category category = new DropTableConfig.Category("ruins_thread", "Ruins", 100.0,
                List.of(new DropTableConfig.Entry("thread_empty", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("ruins_thread", category));
        when(itemResolver.create(eq("thread_empty"))).thenReturn(Optional.of(new ItemStack(Material.PAPER)));

        PlayerInventory inv = player.getInventory();
        inv.setItemInMainHand(new ItemStack(Material.DIAMOND_SHOVEL));

        // A fully-mocked Block: MockBukkit's real-world getDrops() simulation doesn't reliably return the
        // block's vanilla drop in a headless test, so it is stubbed directly to make the digging_break
        // classification (block AND its drop both listed) deterministic.
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.DIRT);
        org.bukkit.Location loc = new org.bukkit.Location(player.getWorld(), 0, 64, 0);
        when(block.getLocation()).thenReturn(loc);
        when(block.getWorld()).thenReturn(player.getWorld());
        when(block.getDrops(any(), any())).thenReturn(List.of(new ItemStack(Material.DIRT)));
        org.bukkit.Chunk chunk = mock(org.bukkit.Chunk.class);
        when(block.getChunk()).thenReturn(chunk);
        org.bukkit.persistence.PersistentDataContainer chunkPdc =
                mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(chunk.getPersistentDataContainer()).thenReturn(chunkPdc);
        when(chunkPdc.getOrDefault(any(), eq(org.bukkit.persistence.PersistentDataType.LONG_ARRAY), any()))
                .thenReturn(new long[0]);

        int itemsBefore = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        listener(mockCatalog()).onBlockBreak(breakEvent(block));

        int itemsAfter = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(1, itemsAfter - itemsBefore,
                "trigger-chance-percent=100 must always draw+drop the sole open entry");
    }
}
