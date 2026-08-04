package com.trinityforge.listeners;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.ArsItemGiveBridge;
import com.trinityforge.stats.CraftQualityService;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemStatProfile;
import com.trinityforge.stats.ItemUseRequirement;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.Damageable;
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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PRG-13 (2026-07-25): SMITHING EXP moved from durability-consumption
 * ({@code NativeSkillExperienceListener#onItemDamage}, now removed) to crafting — mirrors the existing
 * ARS_SMITHING craft-EXP grant in {@link CraftQualityListener#onCraft}, gated by the same
 * {@code CraftQualityConfig#categorySkill()} fixed map ({@code weapon/armor/tool -> SMITHING}).
 *
 * <p>MockBukkit-backed (like {@code VeinMiningListenerTest}) so plain {@link ItemStack} meta reads
 * ({@code hasItemMeta}/{@code getItemMeta}) work without a full server boot. The
 * {@link TrinityForge#getInstance()} singleton is stubbed via {@link TrinityForgeSingletonTestSupport}
 * because {@link com.trinityforge.integration.ars.ArsProgressionBridge#grantSkillExp} reads it.
 */
class CraftQualityListenerSmithingExpTest {

    private static final double SMITHING_EXP_PER_CRAFT = 15.0;

    private ServerMock server;
    private NativeExperienceDispatcher dispatcher;
    private CraftQualityConfig craftQualityConfig;
    private ItemStatsConfig itemStats;
    private SkillExpConfig skillExp;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dispatcher = mock(NativeExperienceDispatcher.class);
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.experienceDispatcher()).thenReturn(dispatcher);

        craftQualityConfig = new CraftQualityConfig(); // real: fixed categorySkill map (weapon/armor/tool -> SMITHING)
        itemStats = mock(ItemStatsConfig.class);
        skillExp = mock(SkillExpConfig.class);
        ConfigManager config = mock(ConfigManager.class);
        when(config.skillExp()).thenReturn(skillExp);
        when(config.itemStats()).thenReturn(itemStats);
        when(tf.config()).thenReturn(config);
        TrinityForgeSingletonTestSupport.set(tf);
        when(skillExp.smithingExpPerCraft()).thenReturn(SMITHING_EXP_PER_CRAFT);
        // 2026-07-30: 素材別EXP表が空 = 従来の定額 exp-per-craft へフォールバックする既定。
        // 素材合計の挙動は smithingExpIsTheSumOfTheMaterialsOnTheGrid が別途検証する。
        when(skillExp.smithingExpPerMaterial()).thenReturn(java.util.Map.of());
        when(skillExp.arsSmithingExpPerCraft()).thenReturn(100.0);
        // 2026-07-28 使用可能レベル連動EXP: このテストは倍率の挙動自体を検証しないので、
        // 常に1.0(影響なし)を返すようスタブする(スタブが無いと Mockito のdouble既定値0.0が
        // 返り、amount<=0.0 で grantSkillExp が早期returnして「呼ばれない」誤検知になる)。
        when(skillExp.useLevelExpMultiplier(org.mockito.ArgumentMatchers.anyString(), anyInt()))
                .thenReturn(1.0);

        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    private CraftQualityListener listener() {
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin();
        ItemFactory itemFactory = mock(ItemFactory.class);
        return listener(plugin, itemFactory);
    }

    private CraftQualityListener listener(org.bukkit.plugin.Plugin plugin, ItemFactory itemFactory) {
        CraftQualityService craftQualityService = mock(CraftQualityService.class);
        when(craftQualityService.rollQuality(any(), any(), anyInt())).thenReturn(0);
        ItemCatalogConfig itemCatalog = mock(ItemCatalogConfig.class);
        return new CraftQualityListener(plugin, itemFactory, craftQualityService, craftQualityConfig,
                skillExp, itemCatalog, itemStats);
    }

    private CraftItemEvent craftEvent(Material resultMaterial, boolean shiftClick) {
        ItemStack result = new ItemStack(resultMaterial);
        when(itemStats.profileFor(eq(resultMaterial), any())).thenReturn(Optional.of(mock(ItemStatProfile.class)));
        when(itemStats.qualityModeOffsetFor(eq(resultMaterial), any())).thenReturn(0);
        // 2026-07-30: 完成品に「使用可能レベル」が無いものには鍛冶EXPを出さないゲートが入ったので、
        // このヘルパで作る完成品には常に使用可能レベルを持たせる(=EXPが出る前提の既存テスト群を維持)。
        // ゲート自体は craftingWithoutUseLevelGrantsNoSmithingExp が別途検証する。
        when(itemStats.useRequirementFor(eq(resultMaterial), any()))
                .thenReturn(Optional.of(new com.trinityforge.stats.ItemUseRequirement(10, SkillId.SMITHING)));

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);

        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        when(event.isShiftClick()).thenReturn(shiftClick);
        stubRealCraftClick(event, shiftClick);
        return event;
    }

    /**
     * 2026-07-30: 「素材を消費して実際に作られるクリック」であることを明示する。
     * {@link CraftQualityListener#producesCraftedItem} が {@code getAction()} を見るようになったため、
     * これを立てないと(Mockito既定の null)クラフト扱いされない。
     */
    private static void stubRealCraftClick(CraftItemEvent event, boolean shiftClick) {
        when(event.getAction()).thenReturn(shiftClick
                ? org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                : org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        when(event.getCursor()).thenReturn(new ItemStack(Material.AIR));
    }

    @Test
    void craftingWeaponGrantsSmithingExpOnce() {
        listener().onCraft(craftEvent(Material.DIAMOND_SWORD, false));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.SMITHING, SMITHING_EXP_PER_CRAFT);
    }

    @Test
    void craftingArmorGrantsSmithingExpOnce() {
        listener().onCraft(craftEvent(Material.DIAMOND_CHESTPLATE, false));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.SMITHING, SMITHING_EXP_PER_CRAFT);
    }

    @Test
    void craftingToolGrantsSmithingExpOnce() {
        listener().onCraft(craftEvent(Material.DIAMOND_PICKAXE, false));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.SMITHING, SMITHING_EXP_PER_CRAFT);
    }

    @Test
    void craftingArsQualityGearUsesArsSmithingAndFinishedItemUseLevel() {
        ItemStack result = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = result.getItemMeta();
        meta.setCustomModelData(400006);
        meta.getPersistentDataContainer().set(
                new NamespacedKey("arspaper", "custom_item_id"),
                PersistentDataType.STRING, "catalyst_test");
        result.setItemMeta(meta);
        when(itemStats.profileFor(eq(Material.BLAZE_ROD), eq(400006)))
                .thenReturn(Optional.of(mock(ItemStatProfile.class)));
        when(itemStats.qualityModeOffsetFor(eq(Material.BLAZE_ROD), eq(400006))).thenReturn(0);
        when(itemStats.useRequirementFor(eq(Material.BLAZE_ROD), eq(400006)))
                .thenReturn(Optional.of(new ItemUseRequirement(55, SkillId.ARS_MAGIC)));
        when(skillExp.useLevelExpMultiplier(SkillId.ARS_SMITHING, 55)).thenReturn(1.55);

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        stubRealCraftClick(event, false);

        try (var ars = mockStatic(ArsItemGiveBridge.class)) {
            ars.when(() -> ArsItemGiveBridge.isQualityStamped("catalyst_test")).thenReturn(true);
            listener().onCraft(event);
        }

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 155.0);
        verify(dispatcher, never()).grant(any(), eq(SkillId.SMITHING), anyDouble());
    }

    /**
     * 2026-08-04 実サーバ報告「広辞苑({@code BOOK#100004})の品質がクラフト時に上がらない」の回帰テスト。
     *
     * <p><b>真因</b>: {@code CraftQualityListener#isStampableCraftResult} の門が
     * <b>バニラ Material が装備扱いか</b>({@code MaterialTier#isEquipment()})だけを見ていた。
     * TF のアイテムは {@code MATERIAL#CMD} 単位で {@code item-stats.yml} にステータスを持つので、
     * ベース素材がバニラ装備でないTF品(広辞苑=BOOK、杖/触媒=BLAZE_ROD 等)は
     * {@code per-quality} を定義していても<b>品質ロールに一度も到達せず常に品質0</b>だった。
     * ユーザー指摘「バグの事象の一つに過ぎない」の通り、広辞苑固有ではなく素材が装備でない
     * 全てのステータス付きTF品が同じ穴に落ちていた。
     */
    @Test
    void craftingNonEquipmentMaterialWithQualityBearingProfileStillRollsQuality() {
        ItemStack result = new ItemStack(Material.BOOK);
        ItemMeta meta = result.getItemMeta();
        meta.setCustomModelData(100004);
        result.setItemMeta(meta);

        ItemStatProfile profile = mock(ItemStatProfile.class);
        when(profile.qualityApplies()).thenReturn(true);
        when(itemStats.profileFor(eq(Material.BOOK), eq(100004))).thenReturn(Optional.of(profile));
        when(itemStats.qualityModeOffsetFor(eq(Material.BOOK), eq(100004))).thenReturn(0);
        when(itemStats.useRequirementFor(eq(Material.BOOK), eq(100004)))
                .thenReturn(Optional.of(new ItemUseRequirement(10, SkillId.SMITHING)));

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        stubRealCraftClick(event, false);

        ItemFactory itemFactory = mock(ItemFactory.class);
        listener(MockBukkit.createMockPlugin(), itemFactory).onCraft(event);

        verify(itemFactory).stamp(any(ItemStack.class),
                org.mockito.ArgumentMatchers.anyLong(), anyInt(), any());
    }

    /**
     * 上の緩和を「品質と無関係なアイテムまで刻む」まで広げていないことを縛る。
     * {@code per-quality}/{@code random} を1つも持たないプロファイル(素材・触媒など、
     * {@code qualityApplies()==false})は、バニラ装備素材でなければ従来どおり刻印しない。
     */
    @Test
    void craftingNonEquipmentMaterialWithoutQualityLayersIsNotStamped() {
        ItemStack result = new ItemStack(Material.BOOK);
        ItemMeta meta = result.getItemMeta();
        meta.setCustomModelData(100009);
        result.setItemMeta(meta);

        ItemStatProfile fixedOnly = mock(ItemStatProfile.class);
        when(fixedOnly.qualityApplies()).thenReturn(false);
        when(itemStats.profileFor(eq(Material.BOOK), eq(100009))).thenReturn(Optional.of(fixedOnly));

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        stubRealCraftClick(event, false);

        ItemFactory itemFactory = mock(ItemFactory.class);
        listener(MockBukkit.createMockPlugin(), itemFactory).onCraft(event);

        verify(itemFactory, never()).stamp(any(ItemStack.class),
                org.mockito.ArgumentMatchers.anyLong(), anyInt(), any());
    }

    @Test
    void craftingArsQualityItemWithoutTfStatsProfileStillGrantsBaseArsSmithingExp() {
        ItemStack result = new ItemStack(Material.BOOK);
        ItemMeta meta = result.getItemMeta();
        meta.setCustomModelData(100001);
        meta.getPersistentDataContainer().set(
                new NamespacedKey("arspaper", "custom_item_id"),
                PersistentDataType.STRING, "spell_book_novice");
        result.setItemMeta(meta);

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        stubRealCraftClick(event, false);

        try (var ars = mockStatic(ArsItemGiveBridge.class)) {
            ars.when(() -> ArsItemGiveBridge.isQualityStamped("spell_book_novice")).thenReturn(true);
            listener().onCraft(event);
        }

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 100.0);
    }

    @Test
    void shiftClickBulkCraftUsesMatrixAndDestinationCapacity() {
        ItemStack result = new ItemStack(Material.DIAMOND_SWORD);
        when(itemStats.profileFor(eq(Material.DIAMOND_SWORD), any()))
                .thenReturn(Optional.of(mock(ItemStatProfile.class)));
        when(itemStats.qualityModeOffsetFor(eq(Material.DIAMOND_SWORD), any())).thenReturn(0);
        when(itemStats.useRequirementFor(eq(Material.DIAMOND_SWORD), any()))
                .thenReturn(Optional.of(new com.trinityforge.stats.ItemUseRequirement(10, SkillId.SMITHING)));
        fillPlayerStorageExcept(2);

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);
        when(inventory.getMatrix()).thenReturn(new ItemStack[]{
                new ItemStack(Material.DIAMOND, 3),
                new ItemStack(Material.DIAMOND, 3),
                new ItemStack(Material.STICK, 3)
        });
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        when(event.isShiftClick()).thenReturn(true);
        stubRealCraftClick(event, true);

        listener().onCraft(event);

        // Matrix permits 3 operations, but two empty equipment slots permit only 2.
        verify(dispatcher).grant(
                player.getUniqueId(), SkillId.SMITHING, SMITHING_EXP_PER_CRAFT * 2);
    }

    /**
     * 2026-07-30「鍛冶のレベルが上がりにくい」への対応: 素材別EXP表が設定されていれば、
     * 鍛冶EXPは<b>盤面に置いた素材の個数分の合計</b>になる(定額 exp-per-craft は使わない)。
     * 表に無い素材は 0 として扱う。
     */
    @Test
    void smithingExpIsTheSumOfTheMaterialsOnTheGrid() {
        when(skillExp.smithingExpPerMaterial())
                .thenReturn(java.util.Map.of("DIAMOND", 25.0, "STICK", 0.5));

        ItemStack result = new ItemStack(Material.DIAMOND_SWORD);
        when(itemStats.profileFor(eq(Material.DIAMOND_SWORD), any()))
                .thenReturn(Optional.of(mock(ItemStatProfile.class)));
        when(itemStats.qualityModeOffsetFor(eq(Material.DIAMOND_SWORD), any())).thenReturn(0);
        when(itemStats.useRequirementFor(eq(Material.DIAMOND_SWORD), any()))
                .thenReturn(Optional.of(new com.trinityforge.stats.ItemUseRequirement(10, SkillId.SMITHING)));

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);
        when(inventory.getMatrix()).thenReturn(new ItemStack[]{
                new ItemStack(Material.DIAMOND, 2),
                new ItemStack(Material.STICK, 1),
                new ItemStack(Material.GOLD_INGOT, 5) // 表に無い素材 = 0
        });
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        when(event.isShiftClick()).thenReturn(false);
        stubRealCraftClick(event, false);

        listener().onCraft(event);

        // ダイヤ2個(25×2) + 棒1本(0.5) + 表に無い金インゴット(0) = 50.5
        verify(dispatcher).grant(player.getUniqueId(), SkillId.SMITHING, 50.5);
    }

    /**
     * 2026-07-30 unzipサイクル対策: 完成品に使用可能レベルが設定されていなければ、鍛冶EXPは
     * 一切付与しない(解体で素材へ戻せる装備を作り直し続ける無限EXP経路を塞ぐ)。
     */
    @Test
    void craftingWithoutUseLevelGrantsNoSmithingExp() {
        ItemStack result = new ItemStack(Material.DIAMOND_SWORD);
        when(itemStats.profileFor(eq(Material.DIAMOND_SWORD), any()))
                .thenReturn(Optional.of(mock(ItemStatProfile.class)));
        when(itemStats.qualityModeOffsetFor(eq(Material.DIAMOND_SWORD), any())).thenReturn(0);
        when(itemStats.useRequirementFor(eq(Material.DIAMOND_SWORD), any())).thenReturn(Optional.empty());

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        when(event.isShiftClick()).thenReturn(false);
        stubRealCraftClick(event, false);

        listener().onCraft(event);

        verify(dispatcher, never()).grant(any(), eq(SkillId.SMITHING), anyDouble());
    }

    @Test
    void shiftClickBulkArsCraftMultipliesArsSmithingExpByOperations() {
        ItemStack result = new ItemStack(Material.BOOK);
        ItemMeta meta = result.getItemMeta();
        meta.setCustomModelData(100001);
        meta.getPersistentDataContainer().set(
                new NamespacedKey("arspaper", "custom_item_id"),
                PersistentDataType.STRING, "spell_book_novice");
        result.setItemMeta(meta);

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);
        when(inventory.getMatrix()).thenReturn(new ItemStack[]{
                new ItemStack(Material.BOOK, 3),
                new ItemStack(Material.AMETHYST_SHARD, 3)
        });
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        when(event.isShiftClick()).thenReturn(true);
        stubRealCraftClick(event, true);

        try (var ars = mockStatic(ArsItemGiveBridge.class)) {
            ars.when(() -> ArsItemGiveBridge.isQualityStamped("spell_book_novice")).thenReturn(true);
            listener().onCraft(event);
        }

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 300.0);
    }

    @Test
    void craftingNonEquipmentGrantsNoSmithingExp() {
        // DIRT is neither tiered equipment nor Ars quality gear, so isStampableCraftResult()
        // rejects it before candidatesFor() is ever consulted.
        listener().onCraft(craftEvent(Material.DIRT, false));

        verify(dispatcher, never()).grant(any(), eq(SkillId.SMITHING), anyDouble());
    }

    @Test
    void missingCraftResultDoesNotStampOrGrantFromCurrentItemOrCursor() {
        ItemStack unrelatedCurrentItem = new ItemStack(Material.DIAMOND_SWORD);
        ItemStack unrelatedCursor = new ItemStack(Material.DIAMOND_PICKAXE);
        when(itemStats.profileFor(eq(Material.DIAMOND_SWORD), any()))
                .thenReturn(Optional.of(mock(ItemStatProfile.class)));
        when(itemStats.profileFor(eq(Material.DIAMOND_PICKAXE), any()))
                .thenReturn(Optional.of(mock(ItemStatProfile.class)));
        player.setItemOnCursor(unrelatedCursor);

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(null);
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(unrelatedCurrentItem);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        stubRealCraftClick(event, false);

        ItemFactory itemFactory = mock(ItemFactory.class);
        listener(MockBukkit.createMockPlugin(), itemFactory).onCraft(event);

        verify(itemFactory, never()).stamp(
                any(ItemStack.class), org.mockito.ArgumentMatchers.anyLong(), anyInt(), any());
        verify(dispatcher, never()).grant(any(), eq(SkillId.SMITHING), anyDouble());
    }

    @Test
    void vanillaSameItemRepairIsNeverStampedOrAwarded() {
        ItemStack first = damaged(Material.WOODEN_SWORD, 50);
        ItemStack second = damaged(Material.WOODEN_SWORD, 50);
        ItemStack repairResult = damaged(Material.WOODEN_SWORD, 39);
        when(itemStats.profileFor(eq(Material.WOODEN_SWORD), any()))
                .thenReturn(Optional.of(mock(ItemStatProfile.class)));

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(repairResult);
        when(inventory.getMatrix()).thenReturn(new ItemStack[]{first, second});
        ItemFactory itemFactory = mock(ItemFactory.class);
        CraftQualityListener listener = listener(MockBukkit.createMockPlugin(), itemFactory);

        PrepareItemCraftEvent prepare = mock(PrepareItemCraftEvent.class);
        when(prepare.getViewers()).thenReturn(java.util.List.of(player));
        when(prepare.getInventory()).thenReturn(inventory);
        when(prepare.isRepair()).thenReturn(true);
        listener.onPrepareCraft(prepare);

        CraftItemEvent craft = mock(CraftItemEvent.class);
        when(craft.getWhoClicked()).thenReturn(player);
        when(craft.getInventory()).thenReturn(inventory);
        when(craft.getRecipe()).thenReturn(mock(Recipe.class));
        stubRealCraftClick(craft, false);
        listener.onCraft(craft);

        verify(itemFactory, never()).stamp(
                any(ItemStack.class), org.mockito.ArgumentMatchers.anyLong(), anyInt(), any());
        verify(dispatcher, never()).grant(any(), eq(SkillId.SMITHING), anyDouble());
    }

    @Test
    void sameTypeInputsWithWrongResultDamageAreNotClassifiedAsVanillaRepair() {
        ItemStack first = damaged(Material.WOODEN_SWORD, 50);
        ItemStack second = damaged(Material.WOODEN_SWORD, 50);

        assertTrue(CraftQualityListener.isVanillaSameItemRepair(
                new ItemStack[]{first, second}, damaged(Material.WOODEN_SWORD, 39)));
        assertFalse(CraftQualityListener.isVanillaSameItemRepair(
                new ItemStack[]{first, second}, damaged(Material.WOODEN_SWORD, 38)));
    }

    @Test
    void customMaxDamageSameItemRepairIsNeverStampedOrAwarded() {
        ItemStack first = damaged(Material.WOODEN_SWORD, 30, 40);
        ItemStack second = damaged(Material.WOODEN_SWORD, 30, 40);
        ItemStack repairResult = damaged(Material.WOODEN_SWORD, 18, 40);
        Damageable firstMeta = (Damageable) first.getItemMeta();
        assertTrue(firstMeta.hasMaxDamage());
        assertEquals(40, firstMeta.getMaxDamage());
        assertEquals(30, firstMeta.getDamage());
        assertTrue(CraftQualityListener.isVanillaSameItemRepair(
                new ItemStack[]{first, second}, repairResult));
        when(itemStats.profileFor(eq(Material.WOODEN_SWORD), any()))
                .thenReturn(Optional.of(mock(ItemStatProfile.class)));

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(repairResult);
        when(inventory.getMatrix()).thenReturn(new ItemStack[]{first, second});
        CraftItemEvent craft = mock(CraftItemEvent.class);
        when(craft.getWhoClicked()).thenReturn(player);
        when(craft.getInventory()).thenReturn(inventory);
        when(craft.getRecipe()).thenReturn(mock(Recipe.class));
        stubRealCraftClick(craft, false);
        ItemFactory itemFactory = mock(ItemFactory.class);

        listener(MockBukkit.createMockPlugin(), itemFactory).onCraft(craft);

        verify(itemFactory, never()).stamp(
                any(ItemStack.class), org.mockito.ArgumentMatchers.anyLong(), anyInt(), any());
        verify(dispatcher, never()).grant(any(), eq(SkillId.SMITHING), anyDouble());
    }

    @Test
    void durabilityLossNoLongerGrantsSmithingExp() {
        // PRG-13 regression guard: NativeSkillExperienceListener#onItemDamage was removed entirely, so
        // there is no code path left that grants SMITHING EXP from PlayerItemDamageEvent. This test
        // documents that guarantee at the type level: the method no longer exists.
        boolean methodStillExists = java.util.Arrays.stream(
                        com.trinityforge.listeners.NativeSkillExperienceListener.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("onItemDamage"));
        org.junit.jupiter.api.Assertions.assertFalse(methodStillExists,
                "onItemDamage must be fully removed, not merely disabled");
    }

    // ------------------------------------------------------------------
    // 2026-07-30: 「クラフトしていないのにクリックごとに鍛冶EXPが入る」不具合の回帰テスト。
    // 別アイテムをカーソルに持って結果枠を左クリックすると、バニラは何もしないが
    // CraftItemEvent は発火する(action = NOTHING)。EXPも品質の振り直しも起きてはいけない。
    // ------------------------------------------------------------------

    @Test
    void clickingResultSlotWhileHoldingAnotherItemGrantsNoExpAndDoesNotRestamp() {
        ItemStack result = new ItemStack(Material.DIAMOND_SWORD);
        when(itemStats.profileFor(eq(Material.DIAMOND_SWORD), any()))
                .thenReturn(Optional.of(mock(ItemStatProfile.class)));
        when(itemStats.qualityModeOffsetFor(eq(Material.DIAMOND_SWORD), any())).thenReturn(0);

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        // 別アイテムを持って結果枠をクリックしたときに Paper が立てる値。
        when(event.getAction()).thenReturn(org.bukkit.event.inventory.InventoryAction.NOTHING);
        when(event.getCursor()).thenReturn(new ItemStack(Material.DIRT));

        ItemFactory itemFactory = mock(ItemFactory.class);
        listener(MockBukkit.createMockPlugin(), itemFactory).onCraft(event);

        verify(dispatcher, never()).grant(any(), eq(SkillId.SMITHING), anyDouble());
        verify(itemFactory, never()).stamp(
                any(ItemStack.class), org.mockito.ArgumentMatchers.anyLong(), anyInt(), any());
        verify(event, never()).setCurrentItem(any(ItemStack.class));
    }

    @Test
    void producesCraftedItemAcceptsOnlyClicksThatConsumeIngredients() {
        ItemStack empty = new ItemStack(Material.AIR);
        ItemStack held = new ItemStack(Material.DIRT);

        // 取り出しが確定する経路
        assertTrue(CraftQualityListener.producesCraftedItem(
                org.bukkit.event.inventory.InventoryAction.PICKUP_ALL, empty));
        assertTrue(CraftQualityListener.producesCraftedItem(
                org.bukkit.event.inventory.InventoryAction.PICKUP_HALF, empty));
        // 同一アイテムでスタックに収まるときだけ PICKUP_* が立つので、カーソルに何か持っていてもよい
        assertTrue(CraftQualityListener.producesCraftedItem(
                org.bukkit.event.inventory.InventoryAction.PICKUP_ALL, held));
        assertTrue(CraftQualityListener.producesCraftedItem(
                org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY, empty));
        assertTrue(CraftQualityListener.producesCraftedItem(
                org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP, empty));
        // Qドロップはカーソルが空のときだけバニラが処理する
        assertTrue(CraftQualityListener.producesCraftedItem(
                org.bukkit.event.inventory.InventoryAction.DROP_ALL_SLOT, empty));
        assertFalse(CraftQualityListener.producesCraftedItem(
                org.bukkit.event.inventory.InventoryAction.DROP_ALL_SLOT, held));

        // クラフトを伴わない経路
        assertFalse(CraftQualityListener.producesCraftedItem(
                org.bukkit.event.inventory.InventoryAction.NOTHING, held));
        assertFalse(CraftQualityListener.producesCraftedItem(
                org.bukkit.event.inventory.InventoryAction.CLONE_STACK, empty));
        assertFalse(CraftQualityListener.producesCraftedItem(
                org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR, held));
        assertFalse(CraftQualityListener.producesCraftedItem(
                org.bukkit.event.inventory.InventoryAction.UNKNOWN, empty));
        assertFalse(CraftQualityListener.producesCraftedItem(null, empty));
    }

    private void fillPlayerStorageExcept(int emptySlots) {
        ItemStack[] storage = new ItemStack[player.getInventory().getStorageContents().length];
        for (int slot = emptySlots; slot < storage.length; slot++) {
            storage[slot] = new ItemStack(Material.DIRT, Material.DIRT.getMaxStackSize());
        }
        player.getInventory().setStorageContents(storage);
    }

    private static ItemStack damaged(Material material, int damage) {
        return damaged(material, damage, null);
    }

    private static ItemStack damaged(Material material, int damage, Integer maxDamage) {
        ItemStack item = new ItemStack(material);
        Damageable meta = (Damageable) item.getItemMeta();
        meta.setMaxDamage(maxDamage);
        meta.setDamage(damage);
        item.setItemMeta(meta);
        return item;
    }
}
