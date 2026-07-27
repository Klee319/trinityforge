package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.FishingGimmickConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.WeaponBaseFormula;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemStatProfile;
import com.trinityforge.stats.PlayerLootLuckSource;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
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

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FishingQualityListener} の FISHINGスキルLv駆動品質刻印(装備釣果、2026-07-23 stat-gate-overhaul
 * §2.3で fishing_luck は比率専用化され品質modeから除去済み) + fishing-bonus追加ドロップ(非装備釣果)を検証
 * する。{@link ItemFactory}/{@link ItemStatsConfig}/{@link CombatDamageConfig}/{@link QualityConfig}/
 * {@link FishingGimmickConfig} はMockitoでモック、{@link Player}/{@link ItemStack}は
 * MockBukkitの{@code ServerMock}で作る実物(PDC読み書きが本物どおり動く必要があるため)。{@link PlayerFishEvent}/
 * {@link Item}(釣果エンティティ)はconcrete class/interfaceをMockitoでモックする({@link PickupQualityListenerTest}
 * と同じ流儀)。
 */
class FishingQualityListenerTest {

    private ServerMock server;
    private ItemFactory itemFactory;
    private ItemStatsConfig itemStats;
    private CombatDamageConfig combatDamage;
    private QualityConfig quality;
    private FishingGimmickConfig fishingGimmick;
    private NamespacedKey treasureFlagKey;
    private FishingQualityListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        itemFactory = mock(ItemFactory.class);
        itemStats = mock(ItemStatsConfig.class);
        combatDamage = mock(CombatDamageConfig.class);
        quality = mock(QualityConfig.class);
        fishingGimmick = mock(FishingGimmickConfig.class);
        treasureFlagKey = new NamespacedKey("trinityforge", "fishing_table_treasure");

        when(itemStats.profileFor(any(), any())).thenReturn(Optional.empty());
        when(itemStats.fallback()).thenReturn(Optional.empty());
        when(itemStats.qualityModeOffsetFor(any(), any())).thenReturn(0);
        when(combatDamage.weaponBaseFormula()).thenReturn(WeaponBaseFormula.disabled());
        when(quality.spreadUp()).thenReturn(1.0);
        when(quality.spreadDown()).thenReturn(1.0);
        when(quality.maxQuality()).thenReturn(9);
        when(fishingGimmick.fishingSkillId()).thenReturn("FISHING");
        when(fishingGimmick.bonusPerLevel()).thenReturn(0.0);
        when(fishingGimmick.treasureMaterials()).thenReturn(java.util.Set.of());

        ItemCatalogConfig itemCatalog = mock(ItemCatalogConfig.class);
        when(itemCatalog.all()).thenReturn(Map.of());

        PlayerLootLuckSource lootLuck = new PlayerLootLuckSource(
                java.util.logging.Logger.getLogger("test"), null);
        // 総合ステータス化(2026-07): fishing-bonus は PlayerStatAggregator 経由で防具・パーク等も合算する。
        // テストではロッドのみ装備なので値は従来どおり。
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                itemStats, combatDamage,
                new PerkBuffResolver(SkillPerkStatSource.EMPTY, java.util.List::of),
                new RoleBuffResolver(new RoleBuffsConfig()));
        listener = new FishingQualityListener(itemFactory,
                quality, fishingGimmick, SkillLevelSource.EMPTY, itemCatalog, lootLuck, aggregator,
                itemStats, treasureFlagKey);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** A mocked {@link Item} whose {@link org.bukkit.persistence.PersistentDataContainer} reports no key
     *  present — the default state for any Item entity that {@link FishingGimmickListener} never touched
     *  (i.e. every scenario in this file except the explicit PDC-flag test below). */
    private Item mockCaughtItem(ItemStack stack) {
        Item caughtItem = mock(Item.class);
        when(caughtItem.getItemStack()).thenReturn(stack);
        org.bukkit.persistence.PersistentDataContainer emptyPdc =
                mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(emptyPdc.has(any(), any())).thenReturn(false);
        when(caughtItem.getPersistentDataContainer()).thenReturn(emptyPdc);
        return caughtItem;
    }

    private PlayerFishEvent fishEvent(PlayerMock player, Item caught) {
        PlayerFishEvent event = mock(PlayerFishEvent.class);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH);
        when(event.getPlayer()).thenReturn(player);
        when(event.getCaught()).thenReturn(caught);
        return event;
    }

    @Test
    void stateNotCaughtFishDoesNothing() {
        PlayerMock player = server.addPlayer();
        Item caughtItem = mock(Item.class);
        PlayerFishEvent event = mock(PlayerFishEvent.class);
        when(event.getState()).thenReturn(PlayerFishEvent.State.FAILED_ATTEMPT);

        listener.onFish(event);

        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
    }

    @Test
    void unstampedEquipmentCatchGetsStamped() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.FISHING_ROD));

        ItemStack caughtStack = new ItemStack(Material.BOW); // BOW = WOODEN tier equipment, unstamped
        Item caughtItem = mock(Item.class);
        when(caughtItem.getItemStack()).thenReturn(caughtStack);
        when(caughtItem.getLocation()).thenReturn(new Location(server.getWorlds().get(0), 0, 64, 0));

        listener.onFish(fishEvent(player, caughtItem));

        verify(itemFactory, times(1)).stamp(any(ItemStack.class), anyLong(), anyInt());
        verify(caughtItem, times(1)).setItemStack(any(ItemStack.class));
    }

    @Test
    void alreadyStampedEquipmentCatchIsNotRestamped() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.FISHING_ROD));

        ItemStack caughtStack = new ItemStack(Material.BOW);
        ItemMeta meta = caughtStack.getItemMeta();
        ItemData.of(meta).setRollSeed(42L);
        caughtStack.setItemMeta(meta);

        Item caughtItem = mock(Item.class);
        when(caughtItem.getItemStack()).thenReturn(caughtStack);

        listener.onFish(fishEvent(player, caughtItem));

        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
        verify(caughtItem, never()).setItemStack(any());
    }

    @Test
    void nonEquipmentCatchWithFishingBonusDropsExtraCopies() {
        // rodにfishing-bonus=3.0(整数)を与える -> 期待値方式で常に+3個追加ドロップ(正規化キーで構成)。
        when(itemStats.profileFor(any(), any())).thenReturn(
                Optional.of(new ItemStatProfile(Map.of("fishing_bonus", 3.0), Map.of(), Map.of())));

        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.FISHING_ROD));
        World world = player.getWorld();
        int itemsBefore = world.getEntitiesByClass(Item.class).size();

        ItemStack caughtStack = new ItemStack(Material.COD); // COD = NONE tier, not equipment
        Item caughtItem = mockCaughtItem(caughtStack);

        listener.onFish(fishEvent(player, caughtItem));

        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
        verify(caughtItem, never()).setItemStack(any());
        int itemsAfter = world.getEntitiesByClass(Item.class).size();
        org.junit.jupiter.api.Assertions.assertEquals(3, itemsAfter - itemsBefore,
                "fishing-bonus=3.0(整数)は常に+3個の追加ドロップになる(期待値方式、端数なし)");
    }

    @Test
    void creativeModePlayerGetsNoStampAndNoBonusDrop() {
        when(itemStats.profileFor(any(), any())).thenReturn(
                Optional.of(new ItemStatProfile(Map.of("fishing_bonus", 3.0), Map.of(), Map.of())));

        PlayerMock player = server.addPlayer();
        player.setGameMode(GameMode.CREATIVE);
        player.getInventory().setItemInMainHand(new ItemStack(Material.FISHING_ROD));
        World world = player.getWorld();
        int itemsBefore = world.getEntitiesByClass(Item.class).size();

        ItemStack caughtStack = new ItemStack(Material.BOW);
        Item caughtItem = mock(Item.class);
        when(caughtItem.getItemStack()).thenReturn(caughtStack);
        when(caughtItem.getLocation()).thenReturn(new Location(world, 0, 64, 0));

        listener.onFish(fishEvent(player, caughtItem));

        verify(itemFactory, never()).stamp(any(), anyLong(), anyInt());
        verify(caughtItem, never()).setItemStack(any());
        int itemsAfter = world.getEntitiesByClass(Item.class).size();
        org.junit.jupiter.api.Assertions.assertEquals(0, itemsAfter - itemsBefore,
                "CREATIVEモードのプレイヤーは品質刻印もfishing-bonus追加ドロップも受けない");
    }

    @Test
    void offHandRodIsUsedWhenMainHandIsNotARod() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.TORCH));
        player.getInventory().setItemInOffHand(new ItemStack(Material.FISHING_ROD));

        ItemStack caughtStack = new ItemStack(Material.BOW);
        Item caughtItem = mock(Item.class);
        when(caughtItem.getItemStack()).thenReturn(caughtStack);
        when(caughtItem.getLocation()).thenReturn(new Location(server.getWorlds().get(0), 0, 64, 0));

        listener.onFish(fishEvent(player, caughtItem));

        verify(itemFactory, times(1)).stamp(any(ItemStack.class), anyLong(), anyInt());
        verify(itemStats, org.mockito.Mockito.atLeastOnce()).profileFor(
                argThat(material -> material == Material.FISHING_ROD), any());
    }

    /**
     * Dupe fix (トレジャー複製, #4): a treasure catch (fallback mode: no PDC flag, on the legacy
     * treasure-materials list) must never receive the fishing-bonus expected-extra-copy roll.
     */
    @Test
    void treasureCatchOnTreasureListNeverGetsDuplicatedByFishingBonus() {
        when(itemStats.profileFor(any(), any())).thenReturn(
                Optional.of(new ItemStatProfile(Map.of("fishing_bonus", 5.0), Map.of(), Map.of())));
        when(fishingGimmick.treasureMaterials()).thenReturn(java.util.Set.of(Material.ENCHANTED_BOOK));

        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.FISHING_ROD));
        World world = player.getWorld();
        int itemsBefore = world.getEntitiesByClass(Item.class).size();

        ItemStack caughtStack = new ItemStack(Material.ENCHANTED_BOOK);
        Item caughtItem = mockCaughtItem(caughtStack);

        listener.onFish(fishEvent(player, caughtItem));

        int itemsAfter = world.getEntitiesByClass(Item.class).size();
        org.junit.jupiter.api.Assertions.assertEquals(0, itemsAfter - itemsBefore,
                "a treasure-list catch must never be duplicated by the fishing-bonus extra-copy roll");
    }

    /** A PDC treasure=true flag (table mode) takes the same short-circuit as the legacy list. */
    @Test
    void treasureFlagOnCaughtEntitySkipsFishingBonus() {
        when(itemStats.profileFor(any(), any())).thenReturn(
                Optional.of(new ItemStatProfile(Map.of("fishing_bonus", 5.0), Map.of(), Map.of())));

        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.FISHING_ROD));
        World world = player.getWorld();
        int itemsBefore = world.getEntitiesByClass(Item.class).size();

        ItemStack caughtStack = new ItemStack(Material.NAME_TAG);
        Item realCaught = world.dropItem(new Location(world, 0, 64, 0), caughtStack);
        realCaught.getPersistentDataContainer().set(treasureFlagKey, PersistentDataType.BOOLEAN, true);

        listener.onFish(fishEvent(player, realCaught));

        int itemsAfter = world.getEntitiesByClass(Item.class).size();
        org.junit.jupiter.api.Assertions.assertEquals(1, itemsAfter - itemsBefore,
                "only the original dropped item exists; the treasure-flagged catch must not be duplicated");
    }

    /** Baseline (non-treasure) non-equipment catch is unaffected. */
    @Test
    void nonTreasureNonEquipmentCatchStillGetsFishingBonus() {
        when(itemStats.profileFor(any(), any())).thenReturn(
                Optional.of(new ItemStatProfile(Map.of("fishing_bonus", 2.0), Map.of(), Map.of())));
        when(fishingGimmick.treasureMaterials()).thenReturn(java.util.Set.of(Material.ENCHANTED_BOOK));

        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.FISHING_ROD));
        World world = player.getWorld();
        int itemsBefore = world.getEntitiesByClass(Item.class).size();

        ItemStack caughtStack = new ItemStack(Material.COD); // not on the treasure list
        Item caughtItem = mockCaughtItem(caughtStack);

        listener.onFish(fishEvent(player, caughtItem));

        int itemsAfter = world.getEntitiesByClass(Item.class).size();
        org.junit.jupiter.api.Assertions.assertEquals(2, itemsAfter - itemsBefore,
                "a non-treasure non-equipment catch must still get its normal fishing-bonus extra copies");
    }
}
