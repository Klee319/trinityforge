package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.config.domains.QualityTiersConfig;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemStatProfile;
import com.trinityforge.stats.PlayerLootLuckSource;
import com.trinityforge.stats.QualityTier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PickupQualityListener} の刻印判定と、ちらつき修正後の「次tick走査」方式を検証する。実際の刻印処理
 * ({@link com.trinityforge.stats.ItemAssembler})はMockito mockに差し替え、{@code itemFactory.stamp(...)}が
 * 呼ばれる/呼ばれないケースだけを {@code verify} で確認する。
 *
 * <p>{@link Player}/{@link ItemStack}/{@link ItemMeta}/スケジューラは MockBukkit の {@code ServerMock} で
 * 生成した実物を使い、{@link ItemData} のPDC読み書き(hasRollSeed判定)とインベントリ走査・次tick実行が本物
 * どおり動くようにする。イベント本体({@link InventoryClickEvent})はMockitoでモックする。
 */
class PickupQualityListenerTest {

    private ServerMock server;
    private ItemFactory itemFactory;
    private ItemStatsConfig itemStats;
    private QualityTiersConfig qualityTiers;
    private PickupQualityListener listener;

    private QualityConfig quality;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        itemFactory = mock(ItemFactory.class);
        itemStats = mock(ItemStatsConfig.class);
        qualityTiers = mock(QualityTiersConfig.class);
        quality = mock(QualityConfig.class);
        when(quality.maxQuality()).thenReturn(9);
        when(quality.spreadUp()).thenReturn(1.5);
        when(quality.spreadDown()).thenReturn(1.5);
        ItemCatalogConfig itemCatalog = mock(ItemCatalogConfig.class);
        when(itemCatalog.all()).thenReturn(java.util.Map.of());
        PlayerLootLuckSource lootLuck = new PlayerLootLuckSource(
                java.util.logging.Logger.getLogger("test"), null);
        listener = new PickupQualityListener(
                MockBukkit.createMockPlugin(), itemFactory, itemStats, qualityTiers, quality, itemCatalog, lootLuck);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack diamondSword() {
        return new ItemStack(Material.DIAMOND_SWORD);
    }

    private void configureConfigured(int tierCount) {
        when(itemStats.profileFor(any(), any()))
                .thenReturn(Optional.of(new ItemStatProfile(Map.of(), Map.of(), Map.of())));
        when(qualityTiers.tiers()).thenReturn(
                java.util.Collections.nCopies(tierCount, new QualityTier("common", "")));
    }

    private void configureUnconfigured() {
        when(itemStats.profileFor(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void sweepStampsAnUnstampedConfiguredItemWhenTiersExist() {
        configureConfigured(3);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, diamondSword());

        listener.sweepInventory(player);

        verify(itemFactory, times(1)).stamp(any(ItemStack.class), anyLong(), anyInt());
    }

    @Test
    void sweepAppliesQualityModeOffsetOnTopOfLootLuck() {
        // 2026-07-23 stat-gate-overhaul §6.6 品質基準値の適用拡大: quality-mode-offset がこのアイテムの
        // ルート品質modeに加算される。offset=maxQuality相当なら、幸運0でもmodeが上限に張り付き常に
        // maxQualityで刻印される(ガウス乱数のブレを打ち消すほど大きいoffsetで決定的に検証)。
        configureConfigured(3);
        when(itemStats.qualityModeOffsetFor(Material.DIAMOND_SWORD, null)).thenReturn(999);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, diamondSword());

        listener.sweepInventory(player);

        org.mockito.ArgumentCaptor<Integer> qualityCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(itemFactory, times(1)).stamp(any(ItemStack.class), anyLong(), qualityCaptor.capture());
        assertEquals(9, qualityCaptor.getValue());
    }

    @Test
    void sweepSkipsAnAlreadyStampedItem() {
        configureConfigured(3);
        PlayerMock player = server.addPlayer();
        ItemStack stack = diamondSword();
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setRollSeed(42L);
        stack.setItemMeta(meta);
        player.getInventory().setItem(0, stack);

        listener.sweepInventory(player);

        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
    }

    @Test
    void sweepSkipsAnItemWithNoConfiguredProfile() {
        configureUnconfigured();
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, diamondSword());

        listener.sweepInventory(player);

        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
    }

    @Test
    void sweepSkipsWhenNoQualityTiersAreConfigured() {
        when(itemStats.profileFor(any(), any()))
                .thenReturn(Optional.of(new ItemStatProfile(Map.of(), Map.of(), Map.of())));
        when(qualityTiers.tiers()).thenReturn(List.of());
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, diamondSword());

        listener.sweepInventory(player);

        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
    }

    @Test
    void onClickSchedulesASweepThatStampsNextTick() {
        configureConfigured(3);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, diamondSword());
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);

        listener.onClick(event);
        // イベント処理中は何も刻印しない(ちらつき防止のためイベント中の書き換えを廃止)。
        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());

        server.getScheduler().performTicks(2);
        verify(itemFactory, times(1)).stamp(any(ItemStack.class), anyLong(), anyInt());
    }

    @Test
    void multipleClicksInSameTickCollapseToOneSweep() {
        configureConfigured(3);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, diamondSword());
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);

        listener.onClick(event);
        listener.onClick(event);
        listener.onClick(event);
        server.getScheduler().performTicks(2);

        // 同一tickの複数イベントは1回の走査に集約 → 1アイテムにつき刻印は1回だけ。
        verify(itemFactory, times(1)).stamp(any(ItemStack.class), anyLong(), anyInt());
    }

    @Test
    void onClickSkipsWhenWhoClickedIsNotAPlayer() {
        configureConfigured(3);
        org.bukkit.entity.HumanEntity nonPlayer = mock(org.bukkit.entity.HumanEntity.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(nonPlayer);

        listener.onClick(event);
        server.getScheduler().performTicks(2);

        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
    }

    /** 既にrollSeed刻印済み(品質確定済み)のアイテムを、任意の {@link BindType} で作る。 */
    private ItemStack boundStamped(BindType bindType) {
        ItemStack stack = diamondSword();
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(1L);
        data.setQuality(2);
        data.setBindType(bindType);
        stack.setItemMeta(meta);
        return stack;
    }

    @Test
    void stampOwnerIfEligibleSkipsOwnerBound() {
        ItemStack stack = boundStamped(BindType.OWNER_BOUND);
        UUID pickerId = UUID.randomUUID();

        boolean stamped = listener.stampOwnerIfEligible(stack, pickerId);

        assertFalse(stamped, "OWNER_BOUND is command-only and must not auto-stamp");
        assertEquals(Optional.empty(), ItemData.of(stack.getItemMeta()).owner());
        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
    }

    @Test
    void stampOwnerIfEligibleStampsPickerAsOwnerForSoulbound() {
        ItemStack stack = boundStamped(BindType.SOULBOUND);
        UUID pickerId = UUID.randomUUID();

        boolean stamped = listener.stampOwnerIfEligible(stack, pickerId);

        assertTrue(stamped, "SOULBOUND with no owner yet must be stamped");
        assertEquals(Optional.of(pickerId), ItemData.of(stack.getItemMeta()).owner());
    }

    @Test
    void stampOwnerIfEligibleSkipsTradeable() {
        ItemStack stack = boundStamped(BindType.TRADEABLE);
        UUID pickerId = UUID.randomUUID();

        boolean stamped = listener.stampOwnerIfEligible(stack, pickerId);

        assertFalse(stamped, "TRADEABLE must never be owner-stamped");
        assertEquals(Optional.empty(), ItemData.of(stack.getItemMeta()).owner());
        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
    }

    @Test
    void stampOwnerIfEligibleSkipsItemsWithNoBindType() {
        ItemStack stack = diamondSword();
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setRollSeed(1L); // 刻印済みだがbindType未設定(catalog経由でない品)
        stack.setItemMeta(meta);
        UUID pickerId = UUID.randomUUID();

        boolean stamped = listener.stampOwnerIfEligible(stack, pickerId);

        assertFalse(stamped);
        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
    }

    @Test
    void stampOwnerIfEligibleDoesNotOverwriteAnExistingOwner() {
        ItemStack stack = boundStamped(BindType.SOULBOUND);
        ItemMeta meta = stack.getItemMeta();
        UUID originalOwner = UUID.randomUUID();
        ItemData.of(meta).setOwner(originalOwner);
        stack.setItemMeta(meta);
        UUID newPicker = UUID.randomUUID();

        boolean stamped = listener.stampOwnerIfEligible(stack, newPicker);

        assertFalse(stamped, "an already-set owner must never be overwritten (D4: idempotent)");
        assertEquals(Optional.of(originalOwner), ItemData.of(stack.getItemMeta()).owner());
        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
    }

    @Test
    void stampOwnerIfEligibleSkipsWhenRollSeedNotYetStamped() {
        ItemStack stack = diamondSword();
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setBindType(BindType.SOULBOUND); // rollSeedは未刻印
        stack.setItemMeta(meta);
        UUID pickerId = UUID.randomUUID();

        boolean stamped = listener.stampOwnerIfEligible(stack, pickerId);

        assertFalse(stamped, "no rollSeed yet means stampIfEligible has not run; owner stamp waits");
        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
    }

    @Test
    void sweepInventoryStampsOwnerAndWritesBackTheSlot() {
        PlayerMock player = server.addPlayer();
        ItemStack stack = boundStamped(BindType.SOULBOUND);
        player.getInventory().setItem(0, stack);
        configureUnconfigured(); // stampIfEligible経路は無関係(既刻印品なのでhasRollSeedでどのみち弾かれる)

        listener.sweepInventory(player);

        ItemStack result = player.getInventory().getItem(0);
        assertEquals(Optional.of(player.getUniqueId()), ItemData.of(result.getItemMeta()).owner());
    }
}
