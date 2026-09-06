package com.trinityforge.listeners;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 苗木の設置印が成長後の根元原木に残ると、一括伐採が根元から発火しなくなる。
 * 成長成功時に印を消す契約を固定する。
 */
class PlacedBlockTrackerGrowthTest {

    private ServerMock server;
    private PlacedBlockTracker tracker;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        tracker = new PlacedBlockTracker(MockBukkit.createMockPlugin());
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("2×2苗木の4座標は成長後に設置印が消える")
    void twoByTwoSaplingMarksAreClearedWhenTheTreeGrows() {
        List<Block> saplings = new ArrayList<>();
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
                Block sapling = world.getBlockAt(x, 64, z);
                sapling.setType(Material.DARK_OAK_SAPLING);
                tracker.markPlaced(sapling);
                saplings.add(sapling);
            }
        }
        Block origin = saplings.get(0);
        List<BlockState> grown = new ArrayList<>();
        for (Block sapling : saplings) {
            sapling.setType(Material.DARK_OAK_LOG);
            grown.add(sapling.getState());
        }

        tracker.clearGrownStructureMarks(origin, grown);

        for (Block stump : saplings) {
            assertFalse(tracker.isPlaced(stump),
                    "成長後の根元に印が残ると一括伐採が根元から発火しない: " + stump.getLocation());
        }
    }

    @Test
    @DisplayName("成長していない設置原木の印は消さない")
    void ungrownPlacedLogKeepsItsMark() {
        Block placed = world.getBlockAt(4, 64, 4);
        placed.setType(Material.OAK_LOG);
        tracker.markPlaced(placed);

        tracker.clearGrownStructureMarks(world.getBlockAt(0, 64, 0), List.of());

        assertTrue(tracker.isPlaced(placed), "丸太建築の印まで消すと置く→壊す伐採ファームが開く");
    }
}
