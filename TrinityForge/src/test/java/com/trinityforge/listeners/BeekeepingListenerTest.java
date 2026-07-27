package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code hive_harvest_fortune} (2026-07-26 ステータス語彙整理: 旧キーから改名): 幸運方式(バニラの
 * Fortuneと同じ考え方)へ再実装。旧実装は {@code MiningGimmickPolicy#percentRoll} 経由で集計済み
 * フラクション値をさらに100分割していたため実効確率が設定値の100分の1になっていた確定バグが
 * あった({@link #hundredXBugRegression_pointTwoIsTwentyPercentNotZeroPointTwoPercent}参照)。
 */
class BeekeepingListenerTest {

    private static PlayerStatAggregator aggregatorReturning(Player player, double fortune) {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(new PlayerCombatAggregate(
                Map.of("hive_harvest_fortune", fortune), Map.of(), Map.of(), Map.of(), Map.of()));
        return aggregator;
    }

    private static PlayerHarvestBlockEvent honeycombHarvestEvent(Player player) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.BEEHIVE);
        PlayerHarvestBlockEvent event = mock(PlayerHarvestBlockEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHarvestedBlock()).thenReturn(block);
        ItemStack honeycomb = new ItemStack(Material.HONEYCOMB, 3);
        when(event.getItemsHarvested()).thenReturn(List.of(honeycomb));
        return event;
    }

    // --- 純粋な算術 (extraHarvests) の極値テスト -----------------------------------------------

    @Test
    void extraHarvests_zeroFortune_alwaysZero() {
        assertEquals(0, BeekeepingListener.extraHarvests(0.0, 0.0));
        assertEquals(0, BeekeepingListener.extraHarvests(0.0, 0.999));
    }

    @Test
    void extraHarvests_oneFortune_alwaysOneRegardlessOfRoll() {
        // f=1.0 → floor=1, 端数=0 → randomRoll に関わらず必ず+1(確定2倍)。
        assertEquals(1, BeekeepingListener.extraHarvests(1.0, 0.0));
        assertEquals(1, BeekeepingListener.extraHarvests(1.0, 0.999));
    }

    @Test
    void extraHarvests_onePointFive_confirmedDoublePlusFiftyPercentTriple() {
        // f=1.5 → floor=1(確定+1)、端数0.5 → roll<0.5なら+1で合計+2(3倍)、roll>=0.5なら+1のまま(2倍)。
        assertEquals(2, BeekeepingListener.extraHarvests(1.5, 0.0));
        assertEquals(2, BeekeepingListener.extraHarvests(1.5, 0.49));
        assertEquals(1, BeekeepingListener.extraHarvests(1.5, 0.5));
        assertEquals(1, BeekeepingListener.extraHarvests(1.5, 0.99));
    }

    @Test
    void hundredXBugRegression_pointTwoIsTwentyPercentNotZeroPointTwoPercent() {
        // 設定値0.20(config上の20)が実効20%として扱われること(旧バグの0.2%ではないこと)を極値で検証。
        // roll=0.19 (<0.20) → +1。roll=0.20 (>=0.20) → +0。旧100xバグならroll=0.002が閾値になっていた。
        assertEquals(1, BeekeepingListener.extraHarvests(0.20, 0.19));
        assertEquals(0, BeekeepingListener.extraHarvests(0.20, 0.20));
    }

    @Test
    void extraHarvests_negativeOrNonFinite_treatedAsZero() {
        assertEquals(0, BeekeepingListener.extraHarvests(-0.5, 0.0));
        assertEquals(0, BeekeepingListener.extraHarvests(Double.NaN, 0.0));
    }

    // --- リスナー全体の end-to-end テスト -------------------------------------------------------

    @Test
    void fullFortuneDeterministicallyDoublesHarvest() {
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        FarmingGimmickConfig gimmickConfig = mock(FarmingGimmickConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 1.0); // 確定+1(2倍)
        BeekeepingListener listener = new BeekeepingListener(dedicatedEffects, gimmickConfig, aggregator);
        PlayerHarvestBlockEvent event = honeycombHarvestEvent(player);

        listener.onHarvestBlock(event);

        assertEquals(6, event.getItemsHarvested().get(0).getAmount());
    }

    @Test
    void zeroFortuneLeavesHarvestUnchanged() {
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        FarmingGimmickConfig gimmickConfig = mock(FarmingGimmickConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 0.0);
        BeekeepingListener listener = new BeekeepingListener(dedicatedEffects, gimmickConfig, aggregator);
        PlayerHarvestBlockEvent event = honeycombHarvestEvent(player);

        listener.onHarvestBlock(event);

        assertEquals(3, event.getItemsHarvested().get(0).getAmount());
    }

    @Test
    void unrelatedDedicatedEffectValueDoesNotDriveFortune() {
        // Regression guard: an unrelated retired dedicated-effect id (DedicatedEffectsConfig) must not
        // affect the harvest — only the hive_harvest_fortune stat key does.
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.valueSum(player, "unrelated-effect")).thenReturn(1000.0);
        FarmingGimmickConfig gimmickConfig = mock(FarmingGimmickConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 0.0);
        BeekeepingListener listener = new BeekeepingListener(dedicatedEffects, gimmickConfig, aggregator);
        PlayerHarvestBlockEvent event = honeycombHarvestEvent(player);

        listener.onHarvestBlock(event);

        assertEquals(3, event.getItemsHarvested().get(0).getAmount());
    }
}
