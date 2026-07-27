package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FoodGimmickConfig;
import org.bukkit.Material;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Set;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FoodBonusListener}: farming.yml A-alpha-2({@code feature:junk-food-restore-boost})が有効な場合、
 * ゴミ食にのみ追加ボーナスが乗り、非ゴミ食には{@code food_restore_bonus}そのものが適用されないこと
 * (「非ゴミ食の満腹度回復量を戻す」)。ノード未保持なら従来通り一律適用される後方互換も検証する。
 */
class FoodBonusListenerTest {

    private static final String FOOD_RESTORE_BONUS = "food_restore_bonus";
    private static final String EFFECT_JUNK_BOOST = "junk-food-restore-boost";

    private ServerMock server;
    private PlayerStatAggregator aggregator;
    private com.trinityforge.combat.PlayerCombatAggregate stats;
    private DedicatedEffectsConfig dedicatedEffects;
    private FoodGimmickConfig foodGimmickConfig;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        aggregator = mock(PlayerStatAggregator.class);
        stats = mock(com.trinityforge.combat.PlayerCombatAggregate.class);
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        foodGimmickConfig = mock(FoodGimmickConfig.class);
        when(foodGimmickConfig.junkFoodMaterials()).thenReturn(Set.of(Material.ROTTEN_FLESH));
        player = server.addPlayer();
        when(aggregator.aggregate(player)).thenReturn(stats);
        when(stats.totalOf(FOOD_RESTORE_BONUS)).thenReturn(0.2); // generic +20%
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private FoodBonusListener listenerWithBoost() {
        return new FoodBonusListener(MockBukkit.createMockPlugin(), aggregator, dedicatedEffects, foodGimmickConfig);
    }

    private FoodBonusListener legacyListener() {
        return new FoodBonusListener(MockBukkit.createMockPlugin(), aggregator);
    }

    private void consume(FoodBonusListener listener, Material material) {
        listener.onConsumeTrackItem(new PlayerItemConsumeEvent(player, new ItemStack(material)));
    }

    @Test
    void nodeUnheldFallsBackToGenericBonusForAnyFood() {
        FoodBonusListener listener = listenerWithBoost();
        when(dedicatedEffects.valueMax(eq(player), eq(EFFECT_JUNK_BOOST))).thenReturn(OptionalDouble.empty());
        consume(listener, Material.COOKED_BEEF);

        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 15);
        player.setFoodLevel(10);
        listener.onFoodChange(event);

        // gained=5, boosted = round(5 * 1.2) = 6 -> 10+6=16
        assertEquals(16, event.getFoodLevel());
    }

    @Test
    void junkFoodGetsGenericPlusJunkBoost() {
        FoodBonusListener listener = listenerWithBoost();
        when(dedicatedEffects.valueMax(eq(player), eq(EFFECT_JUNK_BOOST))).thenReturn(OptionalDouble.of(30.0));
        consume(listener, Material.ROTTEN_FLESH);

        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 15);
        player.setFoodLevel(10);
        listener.onFoodChange(event);

        // gained=5, bonus = 0.2 + 0.3 = 0.5, boosted = round(5*1.5)=8 -> 10+8=18
        assertEquals(18, event.getFoodLevel());
    }

    @Test
    void nonJunkFoodGetsNoBonusWhenNodeActive() {
        FoodBonusListener listener = listenerWithBoost();
        when(dedicatedEffects.valueMax(eq(player), eq(EFFECT_JUNK_BOOST))).thenReturn(OptionalDouble.of(30.0));
        consume(listener, Material.COOKED_BEEF);

        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 15);
        player.setFoodLevel(10);
        listener.onFoodChange(event);

        // node active + non-junk -> food_restore_bonus reverted to 0 -> gained stays 5 -> 10+5=15
        assertEquals(15, event.getFoodLevel());
    }

    @Test
    void legacyTwoArgConstructorAppliesGenericBonusUnconditionally() {
        FoodBonusListener listener = legacyListener();
        consume(listener, Material.ROTTEN_FLESH);

        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 15);
        player.setFoodLevel(10);
        listener.onFoodChange(event);

        assertEquals(16, event.getFoodLevel(), "no dedicatedEffects/foodGimmickConfig wired -> unconditional generic bonus");
    }
}
