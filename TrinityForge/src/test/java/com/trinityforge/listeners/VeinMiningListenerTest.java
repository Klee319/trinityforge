package com.trinityforge.listeners;

import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.MiningGimmickConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link VeinMiningListener#onBlockBreakDropTables}: 2026-07-23 verifier指摘⑧ — drop-table roll is its
 * own {@code MONITOR}+{@code ignoreCancelled=true} handler, gated on the ore-block list AND excluding
 * player-placed blocks ({@link PlacedBlockTracker#isPlaced}, 読み取り専用).
 */
class VeinMiningListenerTest {

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private MiningGimmickConfig gimmickConfig;
    private CrossPluginItemResolver itemResolver;
    private PlacedBlockTracker placedBlockTracker;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(MiningGimmickConfig.class);
        itemResolver = mock(CrossPluginItemResolver.class);
        placedBlockTracker = new PlacedBlockTracker(MockBukkit.createMockPlugin());
        when(dedicatedEffects.dropGatePerks()).thenReturn(Map.of());
        player = server.addPlayer();
        // 2026-07-27: 一括破壊にツール判定(GatheringToolMatcher)が入ったため、素手のままだと
        // 発動しない。素のバニラのツルハシはマテリアル推論で MINING として通る。
        player.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.DIAMOND_PICKAXE));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private VeinMiningListener listener() {
        return new VeinMiningListener(dedicatedEffects, gimmickConfig, itemResolver, placedBlockTracker,
                new FeedbackLayer());
    }

    private BlockBreakEvent breakEvent(Block block) {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @Test
    void ignoresBlockNotInOreList() {
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        DropTableConfig.Category category = new DropTableConfig.Category("gacha", "Gacha", 100.0,
                List.of(new DropTableConfig.Entry("tf_gacha_ticket_1", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha", category));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.STONE);

        listener().onBlockBreakDropTables(breakEvent(block));

        verifyNoInteractions(itemResolver);
    }

    @Test
    void ignoresPlayerPlacedOreBlock() {
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        DropTableConfig.Category category = new DropTableConfig.Category("gacha", "Gacha", 100.0,
                List.of(new DropTableConfig.Entry("tf_gacha_ticket_1", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha", category));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.DIAMOND_ORE);
        placedBlockTracker.markPlaced(block);

        listener().onBlockBreakDropTables(breakEvent(block));

        verifyNoInteractions(itemResolver);
    }

    @Test
    void oreBreakRollsDropTableWhenNotPlacedByPlayer() {
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        DropTableConfig.Category category = new DropTableConfig.Category("gacha", "Gacha", 100.0,
                List.of(new DropTableConfig.Entry("tf_gacha_ticket_1", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha", category));
        when(itemResolver.create(eq("tf_gacha_ticket_1")))
                .thenReturn(Optional.of(new ItemStack(Material.PAPER)));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.DIAMOND_ORE);

        int itemsBefore = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        listener().onBlockBreakDropTables(breakEvent(block));

        int itemsAfter = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(1, itemsAfter - itemsBefore,
                "trigger-chance-percent=100 must always draw+drop the sole open entry");
    }

    @Test
    void veinMiningToggleOffPreventsChainBreakEvenWhenUnlocked() {
        // 2026-07-25 gather-rework-active-framework §2 B-2: プレイヤートグルOFFなら一括破壊しない。
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        when(dedicatedEffects.valueMax(any(), eq("vein-mining"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.veinMiningMaxExtraBlocks(1)).thenReturn(8);
        PlayerData.of(player).setVeinMiningEnabled(false);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.DIAMOND_ORE);
        neighbor.setType(Material.DIAMOND_ORE);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.DIAMOND_ORE, neighbor.getType(), "toggle OFF must not chain-break neighbors");
    }

    @Test
    void placedOriginOreBlockNeverTriggersChainBreak() {
        // GTH-02 exploit fix: silk-touch-preserve + place-a-grid + fortune-break-the-origin must not
        // chain-break the (placed) neighbors too — the placed origin itself must block the trigger.
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        when(dedicatedEffects.valueMax(any(), eq("vein-mining"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.veinMiningMaxExtraBlocks(1)).thenReturn(8);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.DIAMOND_ORE);
        neighbor.setType(Material.DIAMOND_ORE);
        placedBlockTracker.markPlaced(origin);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.DIAMOND_ORE, neighbor.getType(),
                "a placed origin block must not trigger a chain-break of its neighbors");
    }

    @Test
    void veinMiningChainBreaksNeighborWhenToggleOnAndUnlocked() {
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        when(dedicatedEffects.valueMax(any(), eq("vein-mining"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.veinMiningMaxExtraBlocks(1)).thenReturn(8);
        // veinMiningEnabled defaults to true; no explicit set needed.

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.DIAMOND_ORE);
        neighbor.setType(Material.DIAMOND_ORE);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.AIR, neighbor.getType(), "toggle ON + unlocked must chain-break the neighbor");
    }
}
