package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.config.domains.QualityTiersConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemStatProfile;
import com.trinityforge.stats.PlayerLootLuckSource;
import com.trinityforge.stats.QualityTier;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自動作業台(Crafter, 1.21)の成果物が<b>必ず最低品質(tier 0)</b>で刻印されることを固定する
 * (2026-08-21 ユーザー指示「自動作業台でできるものはすべて劣悪品質にしてほしい」)。
 *
 * <p><b>塞いでいる穴</b>: Crafter にはクラフトしたプレイヤーが居ないので成果物は未刻印で出てくる。
 * 未刻印品は {@link PickupQualityListener#stampIfEligible} が<b>拾った人の開運</b>で品質を決めるため、
 * 「自動作業台で量産 → 開運の高い人が拾う」が腕でも運でもない品質稼ぎになっていた。
 *
 * <p>ここで縛るのは<b>挙動</b>であって値ではない: (1) 未刻印の設定済みアイテムは品質0で刻印される、
 * (2) 開運も乱数も参照しない（何度やっても0）、(3) 既に品質が決まっているもの
 * （刻印済み／儀式の品質未決定マーカー）には手を出さない。
 */
class CrafterWorstQualityTest {

    private ServerMock server;
    private ItemFactory itemFactory;
    private ItemStatsConfig itemStats;
    private QualityTiersConfig qualityTiers;
    private PickupQualityListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        itemFactory = mock(ItemFactory.class);
        itemStats = mock(ItemStatsConfig.class);
        qualityTiers = mock(QualityTiersConfig.class);
        QualityConfig quality = mock(QualityConfig.class);
        when(quality.maxQuality()).thenReturn(9);
        when(quality.spreadUp()).thenReturn(1.5);
        when(quality.spreadDown()).thenReturn(1.5);
        // 「開運が高いのに劣悪になる」ことを見たいので、基準品質をわざと高く積んでおく。
        when(quality.lootBaseQuality()).thenReturn(7);
        ItemCatalogConfig itemCatalog = mock(ItemCatalogConfig.class);
        when(itemCatalog.all()).thenReturn(Map.of());
        listener = new PickupQualityListener(
                MockBukkit.createMockPlugin(), itemFactory, itemStats, qualityTiers, quality, itemCatalog,
                new PlayerLootLuckSource(java.util.logging.Logger.getLogger("test"), null));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void configured() {
        when(itemStats.profileFor(any(), any()))
                .thenReturn(Optional.of(new ItemStatProfile(Map.of(), Map.of(), Map.of())));
        when(qualityTiers.tiers()).thenReturn(
                Collections.nCopies(10, new QualityTier("common", "")));
    }

    @Test
    @DisplayName("未刻印の設定済みアイテムは品質0(劣悪)で刻印される")
    void unstampedResultIsStampedAtTierZero() {
        configured();
        ItemStack result = new ItemStack(Material.DIAMOND_SWORD);

        assertTrue(listener.stampAtWorstQuality(result), "刻印したなら true を返すこと");
        verify(itemFactory, times(1)).stamp(eq(result), anyLong(), eq(0));
    }

    @Test
    @DisplayName("何度作っても品質0のまま(開運も乱数も参照しない)")
    void theQualityNeverVariesAcrossRuns() {
        configured();
        for (int i = 0; i < 50; i++) {
            ItemStack result = new ItemStack(Material.DIAMOND_SWORD);
            assertTrue(listener.stampAtWorstQuality(result));
        }
        // 1回でも 0 以外が出たらこの verify が落ちる（= 開運/乱数が混ざった）。
        verify(itemFactory, times(50)).stamp(any(), anyLong(), eq(0));
    }

    @Test
    @DisplayName("既に刻印済みの成果物には触らない(カタログレシピが決めた品質を壊さない)")
    void alreadyStampedResultsAreLeftAlone() {
        configured();
        ItemStack result = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = result.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(1234L);
        data.setQuality(8);
        result.setItemMeta(meta);

        assertFalse(listener.stampAtWorstQuality(result));
        verify(itemFactory, never()).stamp(any(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("CrafterCraftEvent 経由でも品質0が入る(配線されていることの確認)")
    void theEventHandlerItselfStampsTheResult() {
        configured();
        org.bukkit.World world = server.addSimpleWorld("crafter");
        org.bukkit.block.Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.CRAFTER);
        ItemStack result = new ItemStack(Material.DIAMOND_SWORD);
        org.bukkit.inventory.ShapedRecipe recipe = new org.bukkit.inventory.ShapedRecipe(
                new org.bukkit.NamespacedKey("test", "dummy"), result);
        recipe.shape("i");
        recipe.setIngredient('i', Material.DIAMOND);

        listener.onCrafterCraft(new org.bukkit.event.block.CrafterCraftEvent(block, recipe, result));

        verify(itemFactory, times(1)).stamp(eq(result), anyLong(), eq(0));
    }

    @Test
    @DisplayName("item-stats.yml に設定の無いアイテムは対象外(素のバニラ品を勝手にTF品にしない)")
    void unconfiguredItemsAreNotStamped() {
        when(itemStats.profileFor(any(), any())).thenReturn(Optional.empty());
        when(qualityTiers.tiers()).thenReturn(
                Collections.nCopies(10, new QualityTier("common", "")));

        assertFalse(listener.stampAtWorstQuality(new ItemStack(Material.STICK)));
        verify(itemFactory, never()).stamp(any(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }
}
