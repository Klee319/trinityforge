package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.EnchantBookshelfConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for {@link EnchantCostReductionListener#reducedCost(int, double)}: the shared cost-shrink
 * formula used by the enchant-table offer/actual-cost handlers and the anvil repair-cost handler.
 */
class EnchantCostReductionListenerTest {

    @Test
    void zeroReductionLeavesCostUnchanged() {
        assertEquals(30, EnchantCostReductionListener.reducedCost(30, 0.0));
    }

    @Test
    void reductionShrinksCostProportionally() {
        // 30 * (1 - 0.5) = 15
        assertEquals(15, EnchantCostReductionListener.reducedCost(30, 0.5));
    }

    @Test
    void reductionNeverDropsBelowMinimumLevelCost() {
        // 30 * (1 - 0.9) = 3, still above the floor -> unaffected by the floor itself
        assertEquals(3, EnchantCostReductionListener.reducedCost(30, 0.9));
        // Small cost with high reduction must still floor at MIN_LEVEL_COST, never 0.
        assertEquals(EnchantCostReductionListener.MIN_LEVEL_COST,
                EnchantCostReductionListener.reducedCost(1, 0.9));
    }

    @Test
    void nonPositiveCostPassesThroughUnchanged() {
        assertEquals(0, EnchantCostReductionListener.reducedCost(0, 0.5));
        assertEquals(-1, EnchantCostReductionListener.reducedCost(-1, 0.5));
    }

    @Test
    void maxReductionConstantIsClampedTo90Percent() {
        assertEquals(0.9, EnchantCostReductionListener.MAX_REDUCTION, 1e-9);
    }

    // --- 本棚パワーconfig化(2026-07-26新設): computeBookshelfRatio / rescaledCost / rescaledLevel ---

    @Test
    void bookshelfRatioIsAlwaysOneUnderVanillaDefaultConfig() {
        // 既定config: max-bookshelves=15, power-per-bookshelf=1.0。0〜20個(15超の非標準配置も含む)で
        // 常に比率1.0(=挙動不変)であることを確認する。これがこの機能の最重要不変条件。
        for (int raw = 0; raw <= 20; raw++) {
            assertEquals(1.0,
                    EnchantCostReductionListener.computeBookshelfRatio(raw, 15, 1.0),
                    1e-9,
                    "raw=" + raw + " should yield ratio 1.0 under vanilla-default config");
        }
    }

    @Test
    void bookshelfRatioIncreasesWhenMaxBookshelvesRaisedAboveVanillaCap() {
        // 本棚20個・config上限20・係数1.0 → 実効パワー20、バニラ実効パワーは15止まり → 比率 20/15。
        double ratio = EnchantCostReductionListener.computeBookshelfRatio(20, 20, 1.0);
        assertEquals(20.0 / 15.0, ratio, 1e-9);
    }

    @Test
    void bookshelfRatioDecreasesWhenMaxBookshelvesLoweredBelowVanillaCap() {
        // 本棚15個・config上限10・係数1.0 → 実効パワー10、バニラ実効パワー15 → 比率 10/15。
        double ratio = EnchantCostReductionListener.computeBookshelfRatio(15, 10, 1.0);
        assertEquals(10.0 / 15.0, ratio, 1e-9);
    }

    @Test
    void bookshelfRatioScalesWithPowerCoefficient() {
        // 本棚10個・config上限15(バニラ相当)・係数2.0 → 実効パワー20、バニラ実効パワー10 → 比率2.0。
        double ratio = EnchantCostReductionListener.computeBookshelfRatio(10, 15, 2.0);
        assertEquals(2.0, ratio, 1e-9);
    }

    @Test
    void bookshelfRatioIsOneWhenZeroBookshelves() {
        // 本棚0個(rawBonus=0)は config に関わらず補正の余地がない(バニラ実効パワーも0)。
        assertEquals(1.0, EnchantCostReductionListener.computeBookshelfRatio(0, 30, 5.0), 1e-9);
    }

    @Test
    void bookshelfRatioClampsNegativeConfigValuesToZero() {
        // 負のmax/係数(config破損等)は0扱いにフォールバックし、例外を投げない。
        double ratio = EnchantCostReductionListener.computeBookshelfRatio(15, -5, -1.0);
        assertEquals(0.0, ratio, 1e-9);
    }

    @Test
    void rescaledCostNoOpWhenRatioIsOne() {
        assertEquals(30, EnchantCostReductionListener.rescaledCost(30, 1.0));
    }

    @Test
    void rescaledCostScalesProportionallyAndFloorsAtMinimum() {
        assertEquals(20, EnchantCostReductionListener.rescaledCost(10, 2.0));
        assertEquals(EnchantCostReductionListener.MIN_LEVEL_COST,
                EnchantCostReductionListener.rescaledCost(1, 0.1));
    }

    @Test
    void rescaledCostPassesThroughNonPositiveCost() {
        assertEquals(0, EnchantCostReductionListener.rescaledCost(0, 2.0));
    }

    @Test
    void rescaledLevelNoOpWhenRatioIsOne() {
        assertEquals(3, EnchantCostReductionListener.rescaledLevel(3, 1.0, 5));
    }

    @Test
    void rescaledLevelScalesAndClampsToVanillaMax() {
        // level=3, ratio=2.0 -> 6だが、バニラ上限5でクランプ。
        assertEquals(5, EnchantCostReductionListener.rescaledLevel(3, 2.0, 5));
    }

    @Test
    void rescaledLevelNeverDropsBelowOne() {
        // level=1, ratio=0.1 -> 0.1丸め0だが、下限1でクランプ。
        assertEquals(1, EnchantCostReductionListener.rescaledLevel(1, 0.1, 5));
    }

    @Test
    void rescaledLevelPassesThroughNonPositiveLevel() {
        assertEquals(0, EnchantCostReductionListener.rescaledLevel(0, 2.0, 5));
    }

    // --- 残タスク4(2026-07-26): PlayerQuitEvent での lastBookshelfRatioByPlayer 掃除 ---

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Double> lastBookshelfRatioByPlayer(EnchantCostReductionListener listener)
            throws ReflectiveOperationException {
        Field field = EnchantCostReductionListener.class.getDeclaredField("lastBookshelfRatioByPlayer");
        field.setAccessible(true);
        return (Map<UUID, Double>) field.get(listener);
    }

    @Test
    void quitRemovesLeftoverBookshelfRatioEntryForThatPlayer() throws ReflectiveOperationException {
        // エンチャント台を開いて onPrepare だけ走り(比率≠1.0を記録)、そのまま onEnchant を経ずに
        // ログアウトしたケースを再現する: 実際にエンチャントせず立ち去ると、修正前は
        // このプレイヤーのエントリが Map に残り続けていた。
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        EnchantCostReductionListener listener = new EnchantCostReductionListener(aggregator, null);
        PlayerMock player = server.addPlayer();

        Map<UUID, Double> cache = lastBookshelfRatioByPlayer(listener);
        cache.put(player.getUniqueId(), 1.5);
        assertTrue(cache.containsKey(player.getUniqueId()), "test setup must seed the leftover entry");

        listener.onQuit(new PlayerQuitEvent(player, "quit"));

        assertFalse(cache.containsKey(player.getUniqueId()),
                "PlayerQuitEvent must remove that player's cached bookshelf ratio");
    }

    @Test
    void quitOnPlayerWithNoCachedRatioIsANoOp() throws ReflectiveOperationException {
        // 既定config(比率は常に1.0)では onPrepare が何も記録しない -> Mapは常に空のまま。
        // このケースで例外を投げないこと(=既定挙動を壊さないこと)を確認する。
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        EnchantCostReductionListener listener = new EnchantCostReductionListener(aggregator, null);
        PlayerMock player = server.addPlayer();

        Map<UUID, Double> cache = lastBookshelfRatioByPlayer(listener);
        assertTrue(cache.isEmpty());

        listener.onQuit(new PlayerQuitEvent(player, "quit"));

        assertTrue(cache.isEmpty());
    }

    @Test
    void reducedOfferCostKeepsDisplayedHintInActualEnchantments() {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        PlayerCombatAggregate aggregate = mock(PlayerCombatAggregate.class);
        when(aggregator.aggregate(org.mockito.ArgumentMatchers.any())).thenReturn(aggregate);
        when(aggregate.totalOf("enchant_cost_reduction")).thenReturn(0.5);
        EnchantCostReductionListener listener = new EnchantCostReductionListener(aggregator, null);
        PlayerMock player = server.addPlayer();

        InventoryView view = player.openInventory(
                server.createInventory(player, org.bukkit.event.inventory.InventoryType.ENCHANTING));
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        EnchantmentOffer[] offers = {
                null,
                null,
                new EnchantmentOffer(Enchantment.SHARPNESS, 2, 30)
        };
        PrepareItemEnchantEvent prepare = mock(PrepareItemEnchantEvent.class);
        when(prepare.getEnchanter()).thenReturn(player);
        when(prepare.getEnchantmentBonus()).thenReturn(15);
        when(prepare.getOffers()).thenReturn(offers);
        listener.onPrepare(prepare);
        assertEquals(15, offers[2].getCost(), "test setup must change the offer cost");

        Map<Enchantment, Integer> actual = new HashMap<>();
        // Paper recalculates this map from the offer cost changed during PrepareItemEnchantEvent.
        // The displayed clue remains SHARPNESS II, while the recalculation can instead yield SMITE II.
        actual.put(Enchantment.SMITE, 2);
        EnchantItemEvent event = new EnchantItemEvent(player, view, block, item, 15, actual,
                Enchantment.SHARPNESS, 2, 2);

        listener.onEnchantHint(event);
        listener.onEnchant(event);

        assertEquals(2, event.getEnchantsToAdd().get(Enchantment.SHARPNESS),
                "the displayed enchantment hint must be present in the actual result");
        assertFalse(event.getEnchantsToAdd().containsKey(Enchantment.SMITE),
                "an enchantment conflicting with the displayed hint must not survive");
    }

    @Test
    void bookshelfRatioDoesNotScaleDisplayedHintLevelTwice() {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        PlayerCombatAggregate aggregate = mock(PlayerCombatAggregate.class);
        when(aggregator.aggregate(org.mockito.ArgumentMatchers.any())).thenReturn(aggregate);
        when(aggregate.totalOf("enchant_cost_reduction")).thenReturn(0.0);
        EnchantBookshelfConfig bookshelf = mock(EnchantBookshelfConfig.class);
        when(bookshelf.maxBookshelves()).thenReturn(15);
        when(bookshelf.powerPerBookshelf()).thenReturn(0.5);
        EnchantCostReductionListener listener = new EnchantCostReductionListener(aggregator, bookshelf);
        PlayerMock player = server.addPlayer();

        InventoryView view = player.openInventory(
                server.createInventory(player, org.bukkit.event.inventory.InventoryType.ENCHANTING));
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        EnchantmentOffer[] offers = {
                null,
                null,
                new EnchantmentOffer(Enchantment.SHARPNESS, 4, 30)
        };
        PrepareItemEnchantEvent prepare = mock(PrepareItemEnchantEvent.class);
        when(prepare.getEnchanter()).thenReturn(player);
        when(prepare.getEnchantmentBonus()).thenReturn(15);
        when(prepare.getOffers()).thenReturn(offers);
        listener.onPrepare(prepare);
        assertEquals(2, offers[2].getEnchantmentLevel(),
                "ratio 0.5 changes the displayed hint from IV to II");

        Map<Enchantment, Integer> actual = new HashMap<>();
        actual.put(Enchantment.SMITE, 4);
        EnchantItemEvent event = new EnchantItemEvent(player, view, block, item, 15, actual,
                Enchantment.SHARPNESS, 2, 2);

        listener.onEnchantHint(event);
        listener.onEnchant(event);

        assertEquals(2, event.getEnchantsToAdd().get(Enchantment.SHARPNESS),
                "the displayed Sharpness II hint must not be scaled a second time to level I");
        assertFalse(event.getEnchantsToAdd().containsKey(Enchantment.SMITE));
    }
}
