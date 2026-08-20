package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.MiningGimmickConfig;
import com.trinityforge.progression.SkillLevelSource;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GTH-01 exploit fix: {@link MiningFortuneListener} must never grant its bonus extra-drop on a
 * player-placed block, mirroring the guard every sibling extra-drop listener already has
 * ({@code VeinMiningListener}, {@code TreeFellingListener}, {@code DiggingGimmickListener},
 * {@code GatheringExtraDropListener}). Without this, a craftable-and-placeable {@code fortune-blocks}
 * entry (e.g. GLOWSTONE: 4 dust -> 1 block via the vanilla recipe) could be placed and re-broken in a
 * loop for unbounded resource growth.
 *
 * <p>Also covers the non-obvious timing bug this fix has to route around: {@link BlockDropItemEvent}
 * fires strictly AFTER a player-initiated break's {@link BlockBreakEvent} finishes ALL priorities
 * including {@code MONITOR}, where {@code NativeSkillExperienceListener#onBlockBreak} already clears the
 * placed-block mark. A naive {@code isPlaced()} read directly inside {@code onBlockDropItem} would
 * therefore always see the mark already cleared for a player-initiated break — this is why
 * {@link MiningFortuneListener#onBlockBreak} caches the placed status at {@code HIGH} (before
 * {@code MONITOR}) for the paired {@code onBlockDropItem} call to consume.
 */
class MiningFortuneListenerPlacedBlockTest {

    private ServerMock server;
    private MiningGimmickConfig gathering;
    private SkillLevelSource skillLevelSource;
    private PlayerStatAggregator aggregator;
    private PlacedBlockTracker placedBlockTracker;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        gathering = mock(MiningGimmickConfig.class);
        when(gathering.fortuneBlocks()).thenReturn(Set.of(Material.GLOWSTONE));
        when(gathering.fortuneSkillId()).thenReturn("MINING");
        when(gathering.fortunePerLevel()).thenReturn(0.01);
        skillLevelSource = playerId -> Map.of("MINING", 0);
        aggregator = mock(PlayerStatAggregator.class);
        PlayerCombatAggregate totals = mock(PlayerCombatAggregate.class);
        // Guaranteed-extra fortune value so ANY grant would be unmistakable in a would-be-buggy test.
        when(totals.totalOf(org.mockito.ArgumentMatchers.anyString())).thenReturn(100.0);
        when(aggregator.aggregate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(totals);
        placedBlockTracker = new PlacedBlockTracker(MockBukkit.createMockPlugin());
        player = server.addPlayer();
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MiningFortuneListener listener() {
        return new MiningFortuneListener(gathering, skillLevelSource, aggregator, placedBlockTracker);
    }

    private BlockBreakEvent breakEvent(Block block) {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private BlockDropItemEvent dropEvent(Block block, BlockState state, org.bukkit.entity.Item primaryDrop) {
        BlockDropItemEvent event = mock(BlockDropItemEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getBlock()).thenReturn(block);
        when(event.getBlockState()).thenReturn(state);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItems()).thenReturn(List.of(primaryDrop));
        return event;
    }

    private org.bukkit.entity.Item spawnDrop(Block block, Material material) {
        return block.getWorld().dropItemNaturally(block.getLocation(), new org.bukkit.inventory.ItemStack(material));
    }

    @Test
    void playerInitiatedBreakOfPlacedBlockGrantsNoBonus() {
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.GLOWSTONE);
        placedBlockTracker.markPlaced(block);
        BlockState state = block.getState();
        org.bukkit.entity.Item primary = spawnDrop(block, Material.GLOWSTONE_DUST);

        int before = block.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        MiningFortuneListener listener = listener();
        // Mirrors the real Bukkit sequence: BlockBreakEvent (HIGH, captures placed status) THEN
        // BlockDropItemEvent (HIGHEST) — NativeSkillExperienceListener's MONITOR clear happens in between
        // in production, which is exactly what pendingPlacedBreakLocation routes around.
        listener.onBlockBreak(breakEvent(block));
        placedBlockTracker.clearIfPlaced(block); // simulate NativeSkillExperienceListener's MONITOR clear
        listener.onBlockDropItem(dropEvent(block, state, primary));

        int after = block.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(before, after, "placed block must not receive the mining-fortune bonus extra drop");
    }

    @Test
    void playerInitiatedBreakOfNaturalBlockStillGrantsBonus() {
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.GLOWSTONE);
        // never markPlaced(): a natural (or silk-touch-obtained-then-mined-elsewhere) block.
        BlockState state = block.getState();
        org.bukkit.entity.Item primary = spawnDrop(block, Material.GLOWSTONE_DUST);

        int before = block.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        MiningFortuneListener listener = listener();
        listener.onBlockBreak(breakEvent(block));
        listener.onBlockDropItem(dropEvent(block, state, primary));

        int after = block.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(before + GatheringPolicyMax(), after,
                "natural block must still receive the full mining-fortune bonus extra drop");
    }

    @Test
    void chainMinedPlacedBlockWithNoPrecedingBlockBreakEventGrantsNoBonus() {
        // Simulates a vein-mining/tree-felling/digging-gimmick chain block: broken via
        // Block#breakNaturally(...), which fires BlockDropItemEvent WITHOUT ever firing BlockBreakEvent
        // first. onBlockBreak() (and its cache) never runs for this block, so the fix must fall back to a
        // direct placedBlockTracker.isPlaced() read — which is still accurate here because
        // clearIfPlaced() is likewise only ever invoked from a BlockBreakEvent handler.
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.GLOWSTONE);
        placedBlockTracker.markPlaced(block);
        BlockState state = block.getState();
        org.bukkit.entity.Item primary = spawnDrop(block, Material.GLOWSTONE_DUST);

        int before = block.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        MiningFortuneListener listener = listener();
        listener.onBlockDropItem(dropEvent(block, state, primary)); // no onBlockBreak() call at all

        int after = block.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(before, after, "chain-mined placed block must not receive the bonus extra drop");
    }

    private static int GatheringPolicyMax() {
        // expectedExtraRate(100.0, 0, 0.003) = 100.0 (2026-07-28 に × 0.30 を撤去したので係数は無い)。
        // MAX_EXTRA(256) には届かず、100.0 は小数部を持たないため RNG の引きに関わらず追加ドロップは
        // 常にちょうど 100 個になる = 「ボーナスが出た」ことを決定的に検証できる。
        return 100;
    }
}
