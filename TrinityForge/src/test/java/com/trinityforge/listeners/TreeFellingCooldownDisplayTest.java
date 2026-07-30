package com.trinityforge.listeners;

import com.trinityforge.active.ActiveCooldownDisplay;
import com.trinityforge.active.ActiveSkillCooldownKeys;
import com.trinityforge.active.ActiveSkillRegistry;
import com.trinityforge.active.CooldownManager;
import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.WoodcuttingGimmickConfig;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TreeFellingCooldownDisplayTest {

    private ServerMock server;
    private PlayerMock player;
    private DedicatedEffectsConfig effects;
    private WoodcuttingGimmickConfig config;
    private CooldownManager cooldowns;
    private FeedbackLayer feedback;
    private ActiveCooldownDisplay display;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        effects = mock(DedicatedEffectsConfig.class);
        config = mock(WoodcuttingGimmickConfig.class);
        cooldowns = new CooldownManager();
        feedback = mock(FeedbackLayer.class);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(any())).thenReturn(new PlayerCombatAggregate(
                Map.of(ActiveSkillCooldownKeys.forSkill("tree-fell"), 0.0),
                Map.of(), Map.of(), Map.of(), Map.of()));
        when(effects.valueMax(any(), eq("tree-fell"))).thenReturn(OptionalDouble.of(1.0));
        when(config.treeFellMaxExtraLogs(1)).thenReturn(8);
        when(config.treeFellCooldownTicks()).thenReturn(200);

        TreeFellingListener treeFell = new TreeFellingListener(
                effects, config, mock(CrossPluginItemResolver.class),
                new PlacedBlockTracker(MockBukkit.createMockPlugin()), feedback,
                cooldowns, aggregator, null);
        display = new ActiveCooldownDisplay(
                MockBukkit.createMockPlugin(), new ActiveSkillRegistry(), effects,
                cooldowns, feedback, aggregator, List.of(treeFell));
        cooldowns.tryConsume(player.getUniqueId(), "tree-fell", 10_000L, System.currentTimeMillis());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void showsTreeFellCooldownWhileHoldingAnEligibleAxe() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_AXE));

        display.run();

        verify(feedback).cooldownTicking(eq(player), eq("一括伐採"), anyLong());
    }

    @Test
    void hidesTreeFellCooldownWhileHoldingAnUnrelatedTool() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));

        display.run();

        verify(feedback, never()).cooldownTicking(any(), anyString(), anyLong());
    }
}
