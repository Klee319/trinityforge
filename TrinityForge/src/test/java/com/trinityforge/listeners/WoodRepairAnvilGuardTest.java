package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.BindType;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 実サーバ報告「元からある防具以外、木材で修繕ができない」(2026-08-05)の真因を固定する。
 *
 * <h2>真因</h2>
 * {@link WoodRepairListener#onPrepareAnvil} は {@code HIGH} で金床の結果を差し込むが、
 * {@link CatalogVanillaOperationGuardListener#onPrepareAnvil} は {@code HIGHEST}(＝必ず後)で
 * 「カタログ品が素材として食われる操作」を {@code setResult(null)} で潰していた。
 * TF の武器・防具はほぼ全部カタログ品なので、<b>木材修繕は金床では必ず無効化され、
 * カタログ品でない=バニラ装備だけが修繕できる</b>状態だった ── 報告の症状そのもの。
 *
 * <h2>このテストが守るもの</h2>
 * 2つのリスナーを<b>実際の優先度順に</b>呼んで、結果が生き残ることを確認する。
 * ガードの除外条件を外すと1件目が落ちる。ガードを緩めすぎ(効果未解放でも許可)にすると2件目が落ちる
 * ── 未解放で許可すると圧縮木材がバニラの修理素材として普通に食われる穴になるため。
 */
class WoodRepairAnvilGuardTest {

    private static final String UNLOCK = "wood-repair-unlock";
    /** 出荷 {@code progression/crafting-features.yml} の実在 ID (ArsPaper materials.yml 側の実体)。 */
    private static final String WOOD_ID = "oak_wood_1x";
    private static final String CATALOG_ID = "tf_test_chestplate";
    private static final int CMD = 990001;

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private CraftingFeaturesConfig features;
    private ItemCatalogConfig catalog;
    private WoodRepairListener woodRepair;
    private CatalogVanillaOperationGuardListener guard;
    private Player player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();

        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.isActive(any(), eq(UNLOCK))).thenReturn(true);

        features = mock(CraftingFeaturesConfig.class);
        when(features.brewUnlocks()).thenReturn(Map.of());
        when(features.woodRepairMaterial(anyString())).thenReturn(null);
        when(features.woodRepairMaterial(WOOD_ID))
                .thenReturn(new CraftingFeaturesConfig.WoodRepairMaterial(200, true));

        ItemTemplate chestplate = new ItemTemplate(
                CATALOG_ID, Material.DIAMOND_CHESTPLATE, "テスト胸当て", CMD,
                BindType.TRADEABLE, 0, null);
        catalog = mock(ItemCatalogConfig.class);
        when(catalog.all()).thenReturn(Map.of(CATALOG_ID, chestplate));
        when(catalog.template(anyString())).thenReturn(Optional.empty());
        when(catalog.template(CATALOG_ID)).thenReturn(Optional.of(chestplate));

        woodRepair = new WoodRepairListener(dedicatedEffects, features);
        // パーティクルシードの除外(2026-08-25 / W-214)はこのテストの対象外なので、
        // シードが1つも定義されていない = 常に「シード付与ではない」状態にしておく。
        com.trinityforge.config.domains.SpecialRewardsConfig specialRewards =
                mock(com.trinityforge.config.domains.SpecialRewardsConfig.class);
        when(specialRewards.particleSeeds()).thenReturn(Map.of());
        guard = new CatalogVanillaOperationGuardListener(catalog, features, dedicatedEffects,
                specialRewards, mock(com.trinityforge.progression.SpecialRewardService.class));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("カタログ品の防具でも木材修繕の結果がガードに潰されない")
    void catalogArmorSurvivesTheVanillaOperationGuard() {
        ItemStack armor = damagedCatalogArmor(300);
        ItemStack wood = compressedWood(1);
        PrepareAnvilEvent event = anvilEvent(armor, wood);

        woodRepair.onPrepareAnvil(event);
        guard.onPrepareAnvil(event);

        verify(event, never()).setResult(null);
        assertTrue(event.getResult() != null, "金床の結果が消されている");
        assertEquals(100, ((Damageable) event.getResult().getItemMeta()).getDamage(),
                "damage 300 のカタログ防具が durability 200 ぶん修繕されていない");
    }

    @Test
    @DisplayName("効果を持っていないなら従来どおり潰す(圧縮木材がバニラ修理素材として食われる穴を空けない)")
    void withoutTheUnlockTheGuardStillBlocks() {
        when(dedicatedEffects.isActive(any(), eq(UNLOCK))).thenReturn(false);
        PrepareAnvilEvent event = anvilEvent(damagedCatalogArmor(300), compressedWood(1));

        woodRepair.onPrepareAnvil(event);
        guard.onPrepareAnvil(event);

        verify(event).setResult(null);
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /** カタログ品の防具(TF の `trinityforge:catalog_id` PDC を持つ)。 */
    @SuppressWarnings("deprecation")
    private static ItemStack damagedCatalogArmor(int damage) {
        ItemStack stack = new ItemStack(Material.DIAMOND_CHESTPLATE);
        ItemMeta meta = stack.getItemMeta();
        // カタログ品判定は CMD と宣言済みテンプレート(素材一致)の両方を要求する。
        meta.setCustomModelData(CMD);
        ItemData.of(meta).setCatalogId(CATALOG_ID);
        ((Damageable) meta).setDamage(damage);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * 圧縮木材は ArsPaper 側の実体なので、刻まれているのは {@code arspaper:custom_item_id} <b>だけ</b>
     * (TF の catalog PDC は付かない)。素材はこの形でしか現れない。
     */
    private static ItemStack compressedWood(int amount) {
        ItemStack stack = new ItemStack(Material.OAK_WOOD, amount);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(
                new NamespacedKey("arspaper", "custom_item_id"), PersistentDataType.STRING, WOOD_ID);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 差し込まれた結果を読み返せるモック(setResult されたらそれを getResult が返す)。 */
    private PrepareAnvilEvent anvilEvent(ItemStack first, ItemStack second) {
        AnvilInventory inventory = mock(AnvilInventory.class);
        when(inventory.getFirstItem()).thenReturn(first);
        when(inventory.getSecondItem()).thenReturn(second);
        // ガードは getContents() を走査してカタログ品の有無を見る(未スタブだと null で NPE)。
        when(inventory.getContents()).thenReturn(new ItemStack[] {first, second, null});

        AnvilView view = mock(AnvilView.class);
        when(view.getPlayer()).thenReturn(player);

        PrepareAnvilEvent event = mock(PrepareAnvilEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view);
        // バニラは「カタログ防具 + 圧縮木材」に結果を出さない前提で null 始まり。
        ItemStack[] current = {null};
        when(event.getResult()).thenAnswer(invocation -> current[0]);
        org.mockito.Mockito.doAnswer(invocation -> {
            current[0] = invocation.getArgument(0, ItemStack.class);
            return null;
        }).when(event).setResult(any());
        return event;
    }
}
