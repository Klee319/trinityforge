package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.EnchantLuckConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EnchantLuckListener}: enchant_luck stat による {@link EnchantItemEvent} 結果の格上げ補正。
 */
class EnchantLuckListenerTest {

    private ServerMock server;
    private PlayerMock player;
    private PlayerStatAggregator aggregator;
    private EnchantLuckConfig config;
    private DedicatedEffectsConfig dedicatedEffects;
    private CraftingFeaturesConfig craftingFeatures;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        aggregator = mock(PlayerStatAggregator.class);
        config = mock(EnchantLuckConfig.class);
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        craftingFeatures = mock(CraftingFeaturesConfig.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private EnchantItemEvent newEvent(Map<Enchantment, Integer> toAdd) {
        InventoryView view = player.openInventory(
                server.createInventory(player, org.bukkit.event.inventory.InventoryType.ENCHANTING));
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        return new EnchantItemEvent(player, view, block, item, 30, toAdd,
                Enchantment.SHARPNESS, 2, 0);
    }

    private void stubAggregateLuck(double luck) {
        PlayerCombatAggregate totals = mock(PlayerCombatAggregate.class);
        when(totals.totalOf("enchant_luck")).thenReturn(luck);
        when(aggregator.aggregate(player)).thenReturn(totals);
    }

    @Test
    void zeroLuckLeavesResultUntouched() {
        stubAggregateLuck(0.0);
        Map<Enchantment, Integer> toAdd = new HashMap<>();
        toAdd.put(Enchantment.SHARPNESS, 1);
        EnchantLuckListener listener = new EnchantLuckListener(
                aggregator, config, dedicatedEffects, craftingFeatures, new Random(1));

        EnchantItemEvent event = newEvent(toAdd);
        listener.onEnchant(event);

        assertEquals(1, event.getEnchantsToAdd().get(Enchantment.SHARPNESS));
    }

    @Test
    void luckAlwaysBoostsLevelWhenChanceIsCertain() {
        stubAggregateLuck(1.0);
        when(config.levelBoostChancePerLuck()).thenReturn(1.0); // 100% per luck point -> deterministic
        when(config.levelBoostMaxSteps()).thenReturn(2);
        when(config.overenchantBonusChancePerLuck()).thenReturn(0.0);
        when(config.extraEnchantChancePerLuck()).thenReturn(0.0);
        // Sharpness vanilla max is 5; start at 3 so two guaranteed +1 steps land within the vanilla cap.
        Map<Enchantment, Integer> toAdd = new HashMap<>();
        toAdd.put(Enchantment.SHARPNESS, 3);
        EnchantLuckListener listener = new EnchantLuckListener(
                aggregator, config, dedicatedEffects, craftingFeatures, new Random(1));

        EnchantItemEvent event = newEvent(toAdd);
        listener.onEnchant(event);

        assertEquals(5, event.getEnchantsToAdd().get(Enchantment.SHARPNESS),
                "guaranteed boost should reach the vanilla cap (5) in 2 steps");
    }

    @Test
    void levelBoostStopsAtVanillaCapWithoutOverenchantUnlock() {
        stubAggregateLuck(1.0);
        when(config.levelBoostChancePerLuck()).thenReturn(1.0);
        when(config.levelBoostMaxSteps()).thenReturn(5);
        when(config.overenchantBonusChancePerLuck()).thenReturn(1.0); // would always boost IF unlocked
        when(config.extraEnchantChancePerLuck()).thenReturn(0.0);
        when(craftingFeatures.overEnchantMaxLevel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(Enchantment.SHARPNESS)))
                .thenReturn(0); // not unlocked -> overEnchantMaxLevel <= vanillaMax
        Map<Enchantment, Integer> toAdd = new HashMap<>();
        toAdd.put(Enchantment.SHARPNESS, 5); // already at vanilla cap
        EnchantLuckListener listener = new EnchantLuckListener(
                aggregator, config, dedicatedEffects, craftingFeatures, new Random(1));

        EnchantItemEvent event = newEvent(toAdd);
        listener.onEnchant(event);

        assertEquals(5, event.getEnchantsToAdd().get(Enchantment.SHARPNESS),
                "without overenchant unlock, must not exceed vanilla max");
    }

    @Test
    void overenchantUnlockAllowsBoostPastVanillaCap() {
        stubAggregateLuck(1.0);
        when(config.levelBoostChancePerLuck()).thenReturn(1.0);
        when(config.levelBoostMaxSteps()).thenReturn(1);
        when(config.overenchantBonusChancePerLuck()).thenReturn(1.0); // certain when unlocked
        when(config.extraEnchantChancePerLuck()).thenReturn(0.0);
        when(craftingFeatures.overEnchantMaxLevel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(Enchantment.SHARPNESS)))
                .thenReturn(7); // unlocked profile raises the absolute cap above vanilla (5)
        Map<Enchantment, Integer> toAdd = new HashMap<>();
        toAdd.put(Enchantment.SHARPNESS, 5); // already at vanilla cap
        EnchantLuckListener listener = new EnchantLuckListener(
                aggregator, config, dedicatedEffects, craftingFeatures, new Random(1));

        EnchantItemEvent event = newEvent(toAdd);
        listener.onEnchant(event);

        assertEquals(6, event.getEnchantsToAdd().get(Enchantment.SHARPNESS),
                "overenchant-unlocked player should boost past vanilla cap");
    }

    @Test
    void extraEnchantChanceAddsOneCompatibleEnchantWhenCertain() {
        stubAggregateLuck(1.0);
        when(config.levelBoostChancePerLuck()).thenReturn(0.0);
        when(config.levelBoostMaxSteps()).thenReturn(0);
        when(config.overenchantBonusChancePerLuck()).thenReturn(0.0);
        when(config.extraEnchantChancePerLuck()).thenReturn(1.0); // certain extra grant
        Map<Enchantment, Integer> toAdd = new HashMap<>();
        toAdd.put(Enchantment.SHARPNESS, 1);
        EnchantLuckListener listener = new EnchantLuckListener(
                aggregator, config, dedicatedEffects, craftingFeatures, new Random(1));

        EnchantItemEvent event = newEvent(toAdd);
        listener.onEnchant(event);

        assertTrue(event.getEnchantsToAdd().size() > 1, "an extra compatible enchant should have been added");
        assertFalse(event.getEnchantsToAdd().containsKey(null));
    }
}
