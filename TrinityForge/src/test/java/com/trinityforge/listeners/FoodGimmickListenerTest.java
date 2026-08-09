package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FoodGimmickConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.skilltree.DedicatedEffectEntry;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code no-food-consume-chance} → {@code food_save_chance} (2026-07-23 stat-gate-overhaul §2 移行B9:
 * 装備+perk合算。100%以上のstat値は{@link FoodGimmickListener}が乱数に関わらず必ず成立させる
 * ({@code Math.min(1.0, fraction)}でクランプするため)ので、決定的にテストできる).
 *
 * <p>2026-07-27: 確定バグの回帰テストを追加(旧実装は{@code totalOf}が返す既にフラクション化済みの値
 * ({@code PercentStatNormalize.RATE_KEYS}参照)を、さらに{@code MiningGimmickPolicy.percentRoll}
 * (0-100スケール前提)で100分割しており、実効確率が設定値の100分の1になっていた)。
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
    void fractionPointTwoSaveChanceKeepsStackAtRollPointOne() {
        // Regression proof for the confirmed double-scaling bug: totalOf() already returns a fraction
        // (0.2 == the "20%" a config author wrote) because food_save_chance is coerced by
        // PercentStatNormalize.RATE_KEYS before aggregation. Under the OLD implementation
        // (MiningGimmickPolicy.percentRoll(0.2, 0.1)) this would divide 0.2 by 100 again -> effective
        // chance 0.2% -> roll 0.1 would MISS (item consumed normally). The fixed listener compares the
        // fraction directly, so roll 0.1 < 0.2 must HIT (item kept).
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        FoodGimmickConfig foodGimmick = mock(FoodGimmickConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 0.2);
        FoodGimmickListener listener =
                new FoodGimmickListener(MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, aggregator);
        PlayerItemConsumeEvent event = consumeEvent(player);

        try (MockedStatic<ThreadLocalRandom> rngStatic = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom rng = mock(ThreadLocalRandom.class);
            rngStatic.when(ThreadLocalRandom::current).thenReturn(rng);
            when(rng.nextDouble()).thenReturn(0.1);

            listener.onItemConsume(event);
        }

        verify(event).setReplacement(org.mockito.ArgumentMatchers.argThat(
                stack -> stack.getType() == Material.BREAD));
    }

    @Test
    void fractionPointTwoSaveChanceConsumesNormallyAtRollPointThree() {
        // Same fraction (0.2 == 20%) but roll 0.3 sits above the threshold -> must MISS (item consumed
        // normally, no setReplacement call) under the fixed implementation (0.3 >= 0.2).
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        FoodGimmickConfig foodGimmick = mock(FoodGimmickConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 0.2);
        FoodGimmickListener listener =
                new FoodGimmickListener(MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, aggregator);
        PlayerItemConsumeEvent event = consumeEvent(player);

        try (MockedStatic<ThreadLocalRandom> rngStatic = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom rng = mock(ThreadLocalRandom.class);
            rngStatic.when(ThreadLocalRandom::current).thenReturn(rng);
            when(rng.nextDouble()).thenReturn(0.3);

            listener.onItemConsume(event);
        }

        verify(event, never()).setReplacement(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void zeroNegativeOrNonFiniteSaveChanceNeverReplacesAndNeverThrows() {
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        FoodGimmickConfig foodGimmick = mock(FoodGimmickConfig.class);

        FoodGimmickListener zeroListener = new FoodGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, aggregatorReturning(player, 0.0));
        PlayerItemConsumeEvent zeroEvent = consumeEvent(player);
        zeroListener.onItemConsume(zeroEvent);

        FoodGimmickListener negativeListener = new FoodGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, aggregatorReturning(player, -5.0));
        PlayerItemConsumeEvent negativeEvent = consumeEvent(player);
        negativeListener.onItemConsume(negativeEvent);

        FoodGimmickListener nanListener = new FoodGimmickListener(MockBukkit.createMockPlugin(),
                dedicatedEffects, foodGimmick, aggregatorReturning(player, Double.NaN));
        PlayerItemConsumeEvent nanEvent = consumeEvent(player);
        nanListener.onItemConsume(nanEvent);

        verify(zeroEvent, never()).setReplacement(org.mockito.ArgumentMatchers.any());
        verify(negativeEvent, never()).setReplacement(org.mockito.ArgumentMatchers.any());
        verify(nanEvent, never()).setReplacement(org.mockito.ArgumentMatchers.any());
    }

    // --- 2026-08-09新設: unregistered-custom-food-ban(「81倍は食用にしない」)のリスナー配線 -------------

    @Test
    void bannedUnregisteredCustomFoodCancelsConsumptionAndShowsMessage() {
        // FoodGimmickConfig#isBannedUnregisteredCustomFood()がtrueを返す場合、setReplacement等の
        // 通常経路には進まず、イベント自体をキャンセルしプレイヤーへ理由を通知する。
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        FoodGimmickConfig foodGimmick = mock(FoodGimmickConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 1000.0); // >=100%だが到達しないはず
        FoodGimmickListener listener =
                new FoodGimmickListener(MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, aggregator);

        ItemStack unregistered = stampArsCustomItemId(Material.APPLE, "apple_2x");
        when(foodGimmick.isBannedUnregisteredCustomFood(unregistered)).thenReturn(true);
        when(foodGimmick.unregisteredCustomFoodBanMessage()).thenReturn("このアイテムは食料として登録されていません");

        PlayerItemConsumeEvent event = mock(PlayerItemConsumeEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(unregistered);

        listener.onItemConsume(event);

        verify(event).setCancelled(true);
        verify(player).sendActionBar(org.mockito.ArgumentMatchers.any(net.kyori.adventure.text.Component.class));
        verify(event, never()).setReplacement(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonBannedFoodIsUnaffectedByUnregisteredCustomFoodBan() {
        // isBannedUnregisteredCustomFood()がfalseなら通常のno-food-consume-chance経路まで進む
        // (registered custom food / plain vanilla / excluded material・idの全4パターンを代表する)。
        Player player = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        FoodGimmickConfig foodGimmick = mock(FoodGimmickConfig.class);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 1000.0); // >=100% -> 必ず成立
        FoodGimmickListener listener =
                new FoodGimmickListener(MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, aggregator);
        PlayerItemConsumeEvent event = consumeEvent(player);
        when(foodGimmick.isBannedUnregisteredCustomFood(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        listener.onItemConsume(event);

        verify(event, never()).setCancelled(true);
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

    @Test
    void customFoodConfiguredAsJunkGetsInversionBonusInsteadOfNonJunkPenalty() {
        PlayerMock player = server.addPlayer();
        player.setFoodLevel(5);
        player.setSaturation(2.0f);

        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.valueMax(player, "junkfood-inversion")).thenReturn(OptionalDouble.of(100.0));
        when(dedicatedEffects.isActive(player, "satiety-buff")).thenReturn(false);
        FoodGimmickConfig foodGimmick = mock(FoodGimmickConfig.class);
        when(foodGimmick.customFood("tf_rotten_ration")).thenReturn(
                java.util.Optional.of(new FoodGimmickConfig.CustomFood(5, 1.0)));
        when(foodGimmick.junkfoodInversionJunkSaturationBonus()).thenReturn(2.0);
        when(foodGimmick.junkfoodInversionNonJunkSaturationPenalty()).thenReturn(1.0);
        ItemStack item = stampArsCustomItemId(Material.BREAD, "tf_rotten_ration");
        when(foodGimmick.isJunkFood(item)).thenReturn(true);
        FoodGimmickListener listener = new FoodGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, mock(PlayerStatAggregator.class));

        FoodLevelChangeEvent event = foodLevelChangeEvent(player, item);
        listener.onFoodLevelChange(event);
        player.setFoodLevel(10);
        server.getScheduler().performTicks(2);

        assertEquals(5.0f, player.getSaturation(), 0.01f,
                "2 pre + 1 custom + 2 junk inversion; old custom path treated it as non-junk (=2)");
    }

    // --- junkfood-inversion (LEVEL, %; 2026-07-27 農業「ゴミ食」段階化) -----------------------------------

    private static FoodGimmickConfig foodGimmickConfigWithInversionDefaults() {
        FoodGimmickConfig foodGimmick = mock(FoodGimmickConfig.class);
        when(foodGimmick.junkFoodMaterials()).thenReturn(Set.of(Material.ROTTEN_FLESH));
        // 2026-07-27: FoodGimmickListener は junkFoodMaterials() ではなく isJunkFood(ItemStack) を呼ぶ
        // ようになったため、こちらも同じ判定(ROTTEN_FLESHのみゴミ食)をスタブする。
        when(foodGimmick.isJunkFood(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ItemStack stack = invocation.getArgument(0);
            return stack != null && stack.getType() == Material.ROTTEN_FLESH;
        });
        // stats/food-gimmick.yml の現行基準量: junkfood-inversion.junk-saturation-bonus=2.0 /
        // non-junk-saturation-penalty=1.0
        when(foodGimmick.junkfoodInversionJunkSaturationBonus()).thenReturn(2.0);
        when(foodGimmick.junkfoodInversionNonJunkSaturationPenalty()).thenReturn(1.0);
        return foodGimmick;
    }

    @Test
    void junkfoodInversionAtValue100MatchesCurrentBaseAmountsExactly() {
        // A-alpha-1(value:100) must reproduce the pre-段階化 behavior exactly: junk food gets the full
        // configured bonus (+2.0), non-junk food gets the full configured penalty (-1.0).
        PlayerMock player = server.addPlayer();
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.valueMax(player, "junkfood-inversion")).thenReturn(OptionalDouble.of(100.0));
        when(dedicatedEffects.isActive(player, "satiety-buff")).thenReturn(false);
        FoodGimmickConfig foodGimmick = foodGimmickConfigWithInversionDefaults();
        FoodGimmickListener listener = new FoodGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, mock(PlayerStatAggregator.class));

        player.setSaturation(5.0f);
        listener.onFoodLevelChange(foodLevelChangeEvent(player, new ItemStack(Material.ROTTEN_FLESH)));
        assertEquals(7.0f, player.getSaturation(), 0.01f, "junk food: +2.0 * (100/100) = +2.0");

        player.setSaturation(5.0f);
        listener.onFoodLevelChange(foodLevelChangeEvent(player, new ItemStack(Material.COOKED_BEEF)));
        assertEquals(4.0f, player.getSaturation(), 0.01f, "non-junk food: -1.0 * (100/100) = -1.0");
    }

    @Test
    void junkfoodInversionAtValue150ScalesBothAmountsByOnePointFive() {
        // A-alpha-2(value:150): the same base amounts, multiplied by 1.5 (junk +3.0, non-junk -1.5).
        PlayerMock player = server.addPlayer();
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.valueMax(player, "junkfood-inversion")).thenReturn(OptionalDouble.of(150.0));
        when(dedicatedEffects.isActive(player, "satiety-buff")).thenReturn(false);
        FoodGimmickConfig foodGimmick = foodGimmickConfigWithInversionDefaults();
        FoodGimmickListener listener = new FoodGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, mock(PlayerStatAggregator.class));

        player.setSaturation(5.0f);
        listener.onFoodLevelChange(foodLevelChangeEvent(player, new ItemStack(Material.ROTTEN_FLESH)));
        assertEquals(8.0f, player.getSaturation(), 0.01f, "junk food: +2.0 * 1.5 = +3.0");

        player.setSaturation(5.0f);
        listener.onFoodLevelChange(foodLevelChangeEvent(player, new ItemStack(Material.COOKED_BEEF)));
        assertEquals(3.5f, player.getSaturation(), 0.01f, "non-junk food: -1.0 * 1.5 = -1.5");
    }

    private static SkillNode farmingNode(String id, String parent, List<DedicatedEffectEntry> effects) {
        return new SkillNode(id, id, 10, SkillRole.GREEK, parent, "A-greek", "STONE", 1, "desc",
                Map.of(), Map.of(), List.of(), List.of(), effects);
    }

    @Test
    void holderOfBothAlphaNodesGetsMaxValueOneFiftyViaRealGateIndex() {
        // Mirrors farming.yml A-alpha-1(value:100, prerequisite of A-alpha-2)/A-alpha-2(value:150): a
        // player holding both perks must resolve to the higher tier's value (150), same "highest held
        // value wins" convention as junk-food-restore-boost (FoodBonusListener).
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A-alpha-1", farmingNode("A-alpha-1", null,
                List.of(new DedicatedEffectEntry("feature:junkfood-inversion", 100.0))));
        nodes.put("A-alpha-2", farmingNode("A-alpha-2", "A-alpha-1",
                List.of(new DedicatedEffectEntry("feature:junkfood-inversion", 150.0))));
        SkillTree tree = new SkillTree("FARMING", "農業", null, "2,10", null, nodes);
        DedicatedEffectsConfig dedicatedEffects = new DedicatedEffectsConfig();
        dedicatedEffects.reindex(List.of(tree));

        PlayerMock player = server.addPlayer();
        PlayerData.of(player).setHeldPerks(List.of(
                "farming_perk_a_alpha_1", "farming_perk_a_alpha_2"));

        assertEquals(OptionalDouble.of(150.0), dedicatedEffects.valueMax(player, "junkfood-inversion"));

        FoodGimmickConfig foodGimmick = foodGimmickConfigWithInversionDefaults();
        FoodGimmickListener listener = new FoodGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, mock(PlayerStatAggregator.class));
        player.setSaturation(5.0f);
        listener.onFoodLevelChange(foodLevelChangeEvent(player, new ItemStack(Material.ROTTEN_FLESH)));
        assertEquals(8.0f, player.getSaturation(), 0.01f, "highest held value (150) wins -> +2.0 * 1.5 = +3.0");
    }

    @Test
    void junkfoodInversionValueMaxEmptyMeansNoAdjustment() {
        // Node not held (valueMax empty): behaves as if junkfood-inversion were entirely absent, matching
        // every other feature's not-held behavior.
        PlayerMock player = server.addPlayer();
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.valueMax(player, "junkfood-inversion")).thenReturn(OptionalDouble.empty());
        when(dedicatedEffects.isActive(player, "satiety-buff")).thenReturn(false);
        FoodGimmickConfig foodGimmick = foodGimmickConfigWithInversionDefaults();
        FoodGimmickListener listener = new FoodGimmickListener(
                MockBukkit.createMockPlugin(), dedicatedEffects, foodGimmick, mock(PlayerStatAggregator.class));

        player.setSaturation(5.0f);
        listener.onFoodLevelChange(foodLevelChangeEvent(player, new ItemStack(Material.ROTTEN_FLESH)));
        assertEquals(5.0f, player.getSaturation(), 0.01f);
    }
}
