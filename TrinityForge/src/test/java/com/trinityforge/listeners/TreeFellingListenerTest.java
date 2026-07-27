package com.trinityforge.listeners;

import com.trinityforge.active.ActiveSkillCooldownKeys;
import com.trinityforge.active.CooldownManager;
import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.WoodcuttingGimmickConfig;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TreeFellingListener#onBlockBreakDropTables}: 2026-07-23 verifier指摘⑧ — drop-table roll is its
 * own {@code MONITOR}+{@code ignoreCancelled=true} handler, and excludes player-placed log/leaves blocks
 * ({@link PlacedBlockTracker#isPlaced}, 読み取り専用) so 原木設置→破壊のリンゴ量産 loop is closed.
 */
class TreeFellingListenerTest {

    private static final String COOLDOWN_REDUCTION_KEY = ActiveSkillCooldownKeys.forSkill("tree-fell");

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private WoodcuttingGimmickConfig gimmickConfig;
    private CrossPluginItemResolver itemResolver;
    private PlacedBlockTracker placedBlockTracker;
    private CooldownManager cooldowns;
    private PlayerStatAggregator aggregator;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(WoodcuttingGimmickConfig.class);
        itemResolver = mock(CrossPluginItemResolver.class);
        placedBlockTracker = new PlacedBlockTracker(MockBukkit.createMockPlugin());
        cooldowns = new CooldownManager();
        aggregator = mock(PlayerStatAggregator.class);
        stubCooldownReduction(0.0);
        when(dedicatedEffects.dropGatePerks()).thenReturn(Map.of());
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * Stubs {@code aggregator.aggregate(any()).totalOf(tree-fell-cooldown-reduction)} (PRG-07: the
     * shared {@link CooldownManager}-backed CT now reads this key instead of the private
     * {@code ConcurrentHashMap} the pre-fix listener used).
     */
    private void stubCooldownReduction(double value) {
        when(aggregator.aggregate(any())).thenReturn(new PlayerCombatAggregate(
                Map.of(COOLDOWN_REDUCTION_KEY, value), Map.of(), Map.of(), Map.of(), Map.of()));
    }

    private TreeFellingListener listener() {
        return new TreeFellingListener(dedicatedEffects, gimmickConfig, itemResolver, placedBlockTracker,
                new FeedbackLayer(), cooldowns, aggregator);
    }

    private BlockBreakEvent breakEvent(Block block) {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @Test
    void ignoresBlockThatIsNeitherLogNorLeaves() {
        DropTableConfig.Category category = new DropTableConfig.Category("apple", "Apple", 100.0,
                List.of(new DropTableConfig.Entry("APPLE", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("apple", category));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.STONE);

        listener().onBlockBreakDropTables(breakEvent(block));

        verifyNoInteractions(itemResolver);
    }

    @Test
    void ignoresPlayerPlacedLogBlock() {
        DropTableConfig.Category category = new DropTableConfig.Category("apple", "Apple", 100.0,
                List.of(new DropTableConfig.Entry("APPLE", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("apple", category));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.OAK_LOG);
        placedBlockTracker.markPlaced(block);

        listener().onBlockBreakDropTables(breakEvent(block));

        verifyNoInteractions(itemResolver);
    }

    @Test
    void logBreakRollsDropTableWhenNotPlacedByPlayer() {
        DropTableConfig.Category category = new DropTableConfig.Category("apple", "Apple", 100.0,
                List.of(new DropTableConfig.Entry("APPLE", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("apple", category));
        when(itemResolver.create(eq("APPLE"))).thenReturn(Optional.of(new ItemStack(Material.APPLE)));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.OAK_LOG);

        int itemsBefore = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        listener().onBlockBreakDropTables(breakEvent(block));

        int itemsAfter = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(1, itemsAfter - itemsBefore,
                "trigger-chance-percent=100 must always draw+drop the sole open entry");
    }

    @Test
    void treeFellToggleOffPreventsChainFellEvenWhenUnlocked() {
        // 2026-07-25 gather-rework-active-framework §2 B-2: プレイヤートグルOFFなら一括伐採しない。
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_AXE));
        when(dedicatedEffects.valueMax(any(), eq("tree-fell"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.treeFellMaxExtraLogs(1)).thenReturn(8);
        when(gimmickConfig.treeFellCooldownTicks()).thenReturn(200);
        PlayerData.of(player).setTreeFellEnabled(false);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.OAK_LOG);
        neighbor.setType(Material.OAK_LOG);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.OAK_LOG, neighbor.getType(), "toggle OFF must not chain-fell neighbors");
    }

    @Test
    void treeFellChainFellsNeighborWhenToggleOnAndUnlocked() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_AXE));
        when(dedicatedEffects.valueMax(any(), eq("tree-fell"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.treeFellMaxExtraLogs(1)).thenReturn(8);
        when(gimmickConfig.treeFellCooldownTicks()).thenReturn(200);
        // treeFellEnabled defaults to true; no explicit set needed.

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.OAK_LOG);
        neighbor.setType(Material.OAK_LOG);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.AIR, neighbor.getType(), "toggle ON + unlocked must chain-fell the neighbor");
    }
}
