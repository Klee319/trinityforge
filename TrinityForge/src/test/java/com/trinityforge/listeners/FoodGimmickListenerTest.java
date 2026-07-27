package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FoodGimmickConfig;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code no-food-consume-chance} → {@code food_save_chance} (2026-07-23 stat-gate-overhaul §2 移行B9:
 * 装備+perk合算。100%以上のstat値は {@link com.trinityforge.mining.MiningGimmickPolicy#percentRoll} が
 * 乱数に関わらず必ず成立させるため、決定的にテストできる).
 */
class FoodGimmickListenerTest {

    private org.mockbukkit.mockbukkit.ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static PlayerStatAggregator aggregatorReturning(Player player, double foodSaveChance) {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(new PlayerCombatAggregate(
                Map.of("food_save_chance", foodSaveChance), Map.of(), Map.of(), Map.of(), Map.of()));
        return aggregator;
    }

    private static PlayerItemConsumeEvent consumeEvent(Player player) {
        PlayerItemConsumeEvent event = mock(PlayerItemConsumeEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(new ItemStack(Material.BREAD));
        return event;
    }

    @Test
    void newFoodSaveChanceStatKeepsTheFullStack() {
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        FoodGimmickConfig foodGimmick = mock(FoodGimmickConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 1000.0); // >=100% → 常に成立
        FoodGimmickListener listener =
                new FoodGimmickListener(MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, aggregator);
        PlayerItemConsumeEvent event = consumeEvent(player);

        listener.onItemConsume(event);

        verify(event).setReplacement(org.mockito.ArgumentMatchers.argThat(
                stack -> stack.getType() == Material.BREAD));
    }

    @Test
    void oldDedicatedEffectValueNoLongerPreventsConsumption() {
        // Regression guard: the retired dedicated-effect id "no-food-consume-chance" must not save the
        // food item anymore — only the new stat key does.
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.valueSum(player, "no-food-consume-chance")).thenReturn(1000.0);
        FoodGimmickConfig foodGimmick = mock(FoodGimmickConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 0.0);
        FoodGimmickListener listener =
                new FoodGimmickListener(MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, aggregator);
        PlayerItemConsumeEvent event = consumeEvent(player);

        listener.onItemConsume(event);

        verify(event, org.mockito.Mockito.never()).setReplacement(org.mockito.ArgumentMatchers.any());
    }

    private static ItemStack stampArsCustomItemId(Material material, String id) {
        ItemStack stack = new ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("arspaper", "custom_item_id"),
                org.bukkit.persistence.PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return stack;
    }

    private static org.bukkit.event.entity.FoodLevelChangeEvent foodLevelChangeEvent(Player player, ItemStack item) {
        org.bukkit.event.entity.FoodLevelChangeEvent event =
                mock(org.bukkit.event.entity.FoodLevelChangeEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getEntity()).thenReturn(player);
        when(event.getItem()).thenReturn(item);
        return event;
    }

    @Test
    void customFoodReplacesNutritionExactlyPerSeededConfig() {
        // Matches the decisions-doc example: compressed_bread_1x (base BREAD, vanilla nutrition=5) with
        // food-level:20/saturation:18.0 must land the player at exactly the configured values (capped at
        // 20), never at pre + vanilla(5) + configured(20).
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        player.setFoodLevel(15);
        player.setSaturation(2.0f);

        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.isActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        FoodGimmickConfig foodGimmick = mock(FoodGimmickConfig.class);
        when(foodGimmick.customFood("compressed_bread_1x")).thenReturn(
                java.util.Optional.of(new FoodGimmickConfig.CustomFood(20, 18.0)));
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin();
        FoodGimmickListener listener = new FoodGimmickListener(plugin, dedicatedEffects, foodGimmick, aggregator);

        ItemStack item = stampArsCustomItemId(Material.BREAD, "compressed_bread_1x");
        org.bukkit.event.entity.FoodLevelChangeEvent event = foodLevelChangeEvent(player, item);

        listener.onFoodLevelChange(event);
        verify(event).setFoodLevel(20); // min(20, 15 + 20)
        // The real Paper/CraftBukkit framework applies event.getFoodLevel() to the player right after the
        // event callback returns (see FoodGimmickListener javadoc); the mocked event here is disconnected
        // from the player, so simulate that landing before the scheduled saturation task runs next tick —
        // PlayerMock enforces saturation<=foodLevel like real FoodData, so leaving foodLevel stale at 15
        // would silently clamp our saturation write and hide a real bug.
        player.setFoodLevel(20);

        server.getScheduler().performTicks(2);
        org.junit.jupiter.api.Assertions.assertEquals(20.0f, player.getSaturation(), 0.01f);
    }

    @Test
    void customFoodDoesNotDoubleCountBaseMaterialNutrition() {
        // A small configured food-level well below what vanilla+configured would sum to, to prove the
        // base Material's own nutrition (BREAD=5) is never separately added on top.
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        player.setFoodLevel(2);
        player.setSaturation(0.0f);

        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.isActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        FoodGimmickConfig foodGimmick = mock(FoodGimmickConfig.class);
        when(foodGimmick.customFood("low_food_test")).thenReturn(
                java.util.Optional.of(new FoodGimmickConfig.CustomFood(6, 1.0)));
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin();
        FoodGimmickListener listener = new FoodGimmickListener(plugin, dedicatedEffects, foodGimmick, aggregator);

        ItemStack item = stampArsCustomItemId(Material.BREAD, "low_food_test");
        org.bukkit.event.entity.FoodLevelChangeEvent event = foodLevelChangeEvent(player, item);

        listener.onFoodLevelChange(event);
        // If BREAD's vanilla nutrition (5) were double-counted this would be min(20, 2+6+5)=13.
        verify(event).setFoodLevel(8); // min(20, 2 + 6)
        player.setFoodLevel(8); // simulate the framework applying the event's result, see test above

        server.getScheduler().performTicks(2);
        org.junit.jupiter.api.Assertions.assertEquals(1.0f, player.getSaturation(), 0.01f); // min(8, 0+1.0)
    }
}
