package com.trinityforge.listeners;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacedBlockTrackerPistonTest {

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
    @DisplayName("置いたブロックをピストンで押すとマークが行き先へ移る")
    void placedMarkMovesWithTheBlock() {
        Block from = world.getBlockAt(0, 64, 0);
        from.setType(Material.COBBLESTONE);
        tracker.markPlaced(from);
        Block to = from.getRelative(BlockFace.EAST);

        tracker.relocateMarks(List.of(from), BlockFace.EAST);

        assertFalse(tracker.isPlaced(from), "移動元にマークが残ると置いてけぼりになる");
        assertTrue(tracker.isPlaced(to), "行き先を壊したときに EXP が入ってしまう");
    }

    @Test
    @DisplayName("未マークのブロック（丸石ジェネレータ等）も押されたら設置扱い")
    void unmarkedMovedBlockIsMarkedPlaced() {
        Block from = world.getBlockAt(2, 64, 0);
        from.setType(Material.COBBLESTONE);
        Block to = from.getRelative(BlockFace.SOUTH);

        tracker.relocateMarks(List.of(from), BlockFace.SOUTH);

        assertTrue(tracker.isPlaced(to), "ピストン農場の丸石を壊すと採取EXPが入っていた");
        assertFalse(tracker.isPlaced(from));
    }
}
