package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FishingGimmickConfig;
import com.trinityforge.economy.EconomyBridge;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FishSellListener}: T2 (2026-07-25経済連携) — fish-sell-toggle保持者が釣った魚を自動売却する
 * フロー、Vault不在時の完全無効化、未登録Materialの除外、exploit対策(1分あたり上限)を検証する。
 */
class FishSellListenerTest {

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private FishingGimmickConfig gimmickConfig;
    private PlayerStatAggregator aggregator;
    private EconomyBridge economyBridge;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(FishingGimmickConfig.class);
        aggregator = mock(PlayerStatAggregator.class);
        economyBridge = mock(EconomyBridge.class);

        PlayerCombatAggregate agg = mock(PlayerCombatAggregate.class);
        when(agg.totalOf(any())).thenReturn(0.0);
        when(aggregator.aggregate(any())).thenReturn(agg);

        when(dedicatedEffects.isActive(any(), eq("fish-sell-toggle"))).thenReturn(true);
        when(economyBridge.available()).thenReturn(true);
        when(gimmickConfig.fishSellMaxPerMinute()).thenReturn(20);

        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private FishSellListener listener() {
        return new FishSellListener(dedicatedEffects, gimmickConfig, aggregator, economyBridge);
    }

    private PlayerFishEvent fishEvent(Item caught) {
        PlayerFishEvent event = mock(PlayerFishEvent.class);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH);
        when(event.getPlayer()).thenReturn(player);
        when(event.getCaught()).thenReturn(caught);
        return event;
    }

    private Item realCaughtItem(Material material) {
        Location loc = new Location(player.getWorld(), 0, 64, 0);
        return player.getWorld().dropItem(loc, new ItemStack(material));
    }

    /** A caught item stamped with a TF catalog id PDC tag, as {@code fish} group draws would carry. */
    private Item realCaughtItemWithCatalogId(Material material, String catalogId) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(PdcKeys.ITEM_CATALOG_ID, PersistentDataType.STRING, catalogId);
        stack.setItemMeta(meta);
        Location loc = new Location(player.getWorld(), 0, 64, 0);
        return player.getWorld().dropItem(loc, stack);
    }

    @Test
    void sellsRegisteredCatchAndDepositsPriceThenRemovesTheItem() {
        when(gimmickConfig.fishSellPriceOf(Material.COD)).thenReturn(Optional.of(2.0));
        when(economyBridge.deposit(eq(player), eq(2.0))).thenReturn(true);

        Item caught = realCaughtItem(Material.COD);
        listener().onFish(fishEvent(caught));

        verify(economyBridge).deposit(player, 2.0);
        assertTrue(caught.isDead(), "a successfully sold catch must be removed from the world");
    }

    @Test
    void unregisteredMaterialIsNotSoldAndItemStaysIntact() {
        when(gimmickConfig.fishSellPriceOf(Material.COD)).thenReturn(Optional.empty());

        Item caught = realCaughtItem(Material.COD);
        listener().onFish(fishEvent(caught));

        verify(economyBridge, never()).deposit(any(), org.mockito.ArgumentMatchers.anyDouble());
        assertFalse(caught.isDead());
    }

    @Test
    void doesNothingWhenPlayerDoesNotHoldFishSellToggle() {
        when(dedicatedEffects.isActive(any(), eq("fish-sell-toggle"))).thenReturn(false);
        when(gimmickConfig.fishSellPriceOf(Material.COD)).thenReturn(Optional.of(2.0));

        Item caught = realCaughtItem(Material.COD);
        listener().onFish(fishEvent(caught));

        verify(economyBridge, never()).deposit(any(), org.mockito.ArgumentMatchers.anyDouble());
        assertFalse(caught.isDead());
    }

    @Test
    void doesNothingWhenVaultIsUnavailable() {
        when(economyBridge.available()).thenReturn(false);
        when(gimmickConfig.fishSellPriceOf(Material.COD)).thenReturn(Optional.of(2.0));

        Item caught = realCaughtItem(Material.COD);
        listener().onFish(fishEvent(caught));

        verify(economyBridge, never()).deposit(any(), org.mockito.ArgumentMatchers.anyDouble());
        assertFalse(caught.isDead(), "Vault unavailable must leave the item obtainable as normal");
    }

    @Test
    void keepsTheItemWhenDepositFails() {
        when(gimmickConfig.fishSellPriceOf(Material.COD)).thenReturn(Optional.of(2.0));
        when(economyBridge.deposit(any(), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(false);

        Item caught = realCaughtItem(Material.COD);
        listener().onFish(fishEvent(caught));

        assertFalse(caught.isDead(), "a failed deposit must never destroy the source item");
    }

    @Test
    void fishSellPriceBonusStatMultipliesTheDepositedAmount() {
        when(gimmickConfig.fishSellPriceOf(Material.COD)).thenReturn(Optional.of(2.0));
        PlayerCombatAggregate agg = mock(PlayerCombatAggregate.class);
        when(agg.totalOf(any())).thenReturn(0.5); // +50%
        when(aggregator.aggregate(player)).thenReturn(agg);
        when(economyBridge.deposit(eq(player), eq(3.0))).thenReturn(true);

        Item caught = realCaughtItem(Material.COD);
        listener().onFish(fishEvent(caught));

        verify(economyBridge).deposit(player, 3.0);
    }

    // ---- T2 (2026-07-25経済連携拡張): fish-sell.prices のカスタムID対応 ----

    @Test
    void sellsCatchPricedByCustomCatalogIdToken() {
        when(gimmickConfig.fishSellPriceOf("tf_golden_koi")).thenReturn(Optional.of(9.0));
        when(economyBridge.deposit(eq(player), eq(9.0))).thenReturn(true);

        Item caught = realCaughtItemWithCatalogId(Material.COD, "tf_golden_koi");
        listener().onFish(fishEvent(caught));

        verify(economyBridge).deposit(player, 9.0);
        assertTrue(caught.isDead());
    }

    @Test
    void customCatalogIdPriceTakesPriorityOverMaterialPriceWhenBothRegistered() {
        // The item is a COD (Material has a registered price too), but it also carries a catalog id whose
        // price must win — the custom id is the more specific match.
        when(gimmickConfig.fishSellPriceOf("tf_golden_koi")).thenReturn(Optional.of(9.0));
        when(gimmickConfig.fishSellPriceOf(Material.COD)).thenReturn(Optional.of(2.0));
        when(economyBridge.deposit(eq(player), eq(9.0))).thenReturn(true);

        Item caught = realCaughtItemWithCatalogId(Material.COD, "tf_golden_koi");
        listener().onFish(fishEvent(caught));

        verify(economyBridge).deposit(player, 9.0);
        verify(economyBridge, never()).deposit(player, 2.0);
    }

    @Test
    void fallsBackToMaterialPriceWhenCatalogIdHasNoRegisteredPrice() {
        // Catalog id present but not in fish-sell.prices -> falls back to the plain Material lookup
        // (backward compat path for the pre-existing 4 vanilla-fish entries).
        when(gimmickConfig.fishSellPriceOf("tf_unpriced_fish")).thenReturn(Optional.empty());
        when(gimmickConfig.fishSellPriceOf(Material.COD)).thenReturn(Optional.of(2.0));
        when(economyBridge.deposit(eq(player), eq(2.0))).thenReturn(true);

        Item caught = realCaughtItemWithCatalogId(Material.COD, "tf_unpriced_fish");
        listener().onFish(fishEvent(caught));

        verify(economyBridge).deposit(player, 2.0);
    }

    @Test
    void exploitGuardStopsSellingPastThePerMinuteCapAndKeepsLaterCatches() {
        // The quota tracker lives on the listener instance, so the SAME instance (mirroring the one
        // TrinityForge registers exactly once at startup) must be reused across catches for this test
        // to exercise the real exploit-guard behavior.
        when(gimmickConfig.fishSellMaxPerMinute()).thenReturn(1);
        when(gimmickConfig.fishSellPriceOf(Material.COD)).thenReturn(Optional.of(2.0));
        when(economyBridge.deposit(any(), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(true);
        FishSellListener shared = listener();

        Item first = realCaughtItem(Material.COD);
        shared.onFish(fishEvent(first));
        Item second = realCaughtItem(Material.COD);
        shared.onFish(fishEvent(second));

        assertTrue(first.isDead(), "first sale within quota must succeed");
        assertFalse(second.isDead(), "second catch within the same 1-minute window must be blocked by the cap");
    }
}
