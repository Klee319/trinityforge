package com.trinityforge.stats;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.progression.SkillLevelSource;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CraftQualityService} 移行B4 (2026-07-23 stat-gate-overhaul §2): クラフト系の全パーク読みは
 * 旧 {@code smithing_*_add}/{@code arssmithing_*_add} (perk専用) から新設の統合stat key
 * (装備+perk合算、{@link PlayerStatAggregator}) へ切替済み。
 */
class CraftQualityServiceTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static PlayerStatAggregator aggregatorReturning(Player player, Map<String, Double> stats) {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(
                new PlayerCombatAggregate(stats, Map.of(), Map.of(), Map.of(), Map.of()));
        return aggregator;
    }

    @Test
    void workbenchQualityBonusDrivesQualityMode() {
        Player player = mock(Player.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, Map.of("workbench_quality_bonus", 3.0));
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, new CraftQualityConfig(), new QualityConfig(), aggregator, null);

        assertEquals(3, service.qualityMode(player, Set.of()));
    }

    @Test
    void oldCraftQualityBonusKeyNoLongerContributesToWorkbenchQuality() {
        // Regression guard (2026-07-26 統合): the retired craft_quality_bonus key must not add to the
        // workbench quality mode anymore — only workbench_quality_bonus does.
        Player player = mock(Player.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, Map.of("craft_quality_bonus", 3.0));
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, new CraftQualityConfig(), new QualityConfig(), aggregator, null);

        assertEquals(0, service.qualityMode(player, Set.of()));
    }

    @Test
    void craftRollModsReadsTheThreeNewRollKeys() {
        Player player = mock(Player.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, Map.of(
                "craft_roll_up_bonus", 10.0,
                "craft_roll_down_reduction", 20.0,
                "craft_roll_inset", 5.0));
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, new CraftQualityConfig(), new QualityConfig(), aggregator, null);

        CraftRollMods mods = service.craftRollMods(player);
        assertEquals(0.10, mods.rollUpBonus(), 1e-9);
        assertEquals(0.20, mods.rollDownReduction(), 1e-9);
        assertEquals(0.05, mods.rollInsetDelta(), 1e-9);
    }

    @Test
    void nullAggregatorFailsSafeToZero() {
        Player player = mock(Player.class);
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, new CraftQualityConfig(), new QualityConfig());

        assertEquals(0, service.qualityMode(player, Set.of()));
        assertEquals(CraftRollMods.NONE, service.craftRollMods(player));
    }

    // ---- minimumQuality: workbench-preview guaranteed floor (task A) ----

    @Test
    void minimumQualityUsesSameModeAsQualityModeButNeverExceedsIt() {
        // QualityConfig() defaults have a positive spread-down, so the true floor collapses to 0 even
        // though the mode itself (workbench_quality_bonus=3) sits at 3.
        Player player = mock(Player.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, Map.of("workbench_quality_bonus", 3.0));
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, new CraftQualityConfig(), new QualityConfig(), aggregator, null);

        assertEquals(3, service.qualityMode(player, Set.of()), "mode itself is unchanged");
        int minimum = service.minimumQuality(player, Set.of());
        assertTrue(minimum <= 3, "guaranteed floor must never exceed the mode");
    }

    @Test
    void minimumQualityCollapsesToModeWhenSpreadDownFullyReducedByPerk() {
        // workbench_downswing_reduction perk can push the effective spread-down to (clamped) 0, in which
        // case the guaranteed floor becomes the mode itself (no downward spread left at all).
        Player player = mock(Player.class);
        QualityConfig quality = new QualityConfig();
        PlayerStatAggregator aggregator = aggregatorReturning(player, Map.of(
                "workbench_quality_bonus", 4.0,
                "workbench_downswing_reduction", 999.0));
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, new CraftQualityConfig(), quality, aggregator, null);

        assertEquals(4, service.minimumQuality(player, Set.of()),
                "spread-down fully neutralized by the perk -> floor equals the mode");
    }

    @Test
    void minimumQualityNullAggregatorFailsSafeToZeroOrBelowMode() {
        Player player = mock(Player.class);
        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, new CraftQualityConfig(), new QualityConfig());

        assertEquals(0, service.qualityMode(player, Set.of()));
        assertTrue(service.minimumQuality(player, Set.of()) <= 0);
    }

    @Test
    void ritualQualityAppliesTheResultItemsQualityModeOffset() {
        Player player = mock(Player.class);
        ItemStatsConfig itemStats = mock(ItemStatsConfig.class);
        when(itemStats.qualityModeOffsetFor(Material.DIAMOND_CHESTPLATE, null)).thenReturn(-8);
        QualityConfig quality = mock(QualityConfig.class);
        when(quality.maxQuality()).thenReturn(15);
        when(quality.spreadUp()).thenReturn(0.0);
        when(quality.spreadDown()).thenReturn(0.0);

        CraftQualityService service = new CraftQualityService(
                SkillLevelSource.EMPTY, new CraftQualityConfig(), quality,
                aggregatorReturning(player, Map.of("ritual_quality_bonus", 15.0)), itemStats);

        assertEquals(7, service.rollArsSmithingQuality(player,
                        new ItemStack(Material.DIAMOND_CHESTPLATE)),
                "儀式品質+15と成果物の-8を合算した実効値は7");
    }
}
