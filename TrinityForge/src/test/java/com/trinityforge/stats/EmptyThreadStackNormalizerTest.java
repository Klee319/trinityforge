package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.listeners.PickupQualityListener;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W-68: 空スレッドは個体 PDC を剥がしてスタック可能にし、品質刻印を二度と焼かない。
 */
class EmptyThreadStackNormalizerTest {

    private static final NamespacedKey ARS_CUSTOM_ITEM_ID =
            new NamespacedKey("arspaper", "custom_item_id");

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack uniqueEmptyThread(long seed, int quality, String loreLine) {
        ItemStack stack = new ItemStack(Material.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(ARS_CUSTOM_ITEM_ID, PersistentDataType.STRING, "thread_empty");
        ItemData data = ItemData.of(meta);
        data.setCatalogId("thread_empty");
        data.setRollSeed(seed);
        data.setQuality(quality);
        data.setTableGeneration(3);
        data.setDataVersion(1);
        meta.lore(List.of(net.kyori.adventure.text.Component.text(loreLine)));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemCatalogConfig catalogWithEmptyThread() {
        ItemTemplate template = new ItemTemplate(
                "thread_empty", Material.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE,
                "<gray>空のスレッド</gray>", 300001, BindType.TRADEABLE, 0, null,
                List.of("<dark_gray>防具のスレッドスロットにセット可能</dark_gray>"),
                List.of(), null, true, ItemTemplate.EXTERNAL_SOURCE_ARSPAPER);
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template("thread_empty")).thenReturn(java.util.Optional.of(template));
        when(catalog.all()).thenReturn(java.util.Map.of("thread_empty", template));
        return catalog;
    }

    @Test
    @DisplayName("個体ごとに違う TF PDC と lore を剥がすと、2本の空スレッドが isSimilar になる")
    void twoUniqueEmptyThreadsBecomeSimilarAfterNormalize() {
        ItemCatalogConfig catalog = catalogWithEmptyThread();
        ItemStack a = uniqueEmptyThread(111L, 4, "個体A");
        ItemStack b = uniqueEmptyThread(222L, 7, "個体B");
        assertFalse(a.isSimilar(b), "正規化前は個体差で重ならない");

        assertTrue(EmptyThreadStackNormalizer.normalize(a, catalog));
        assertTrue(EmptyThreadStackNormalizer.normalize(b, catalog));

        ItemMeta metaA = a.getItemMeta();
        assertFalse(ItemData.of(metaA).hasRollSeed());
        assertEquals("thread_empty", ItemData.of(metaA).catalogId().orElseThrow());
        assertEquals("thread_empty", metaA.getPersistentDataContainer()
                .get(ARS_CUSTOM_ITEM_ID, PersistentDataType.STRING));
        assertTrue(a.isSimilar(b), "PDC と lore を揃えたら重なる");
    }

    @Test
    @DisplayName("PickupQualityListener は空スレッドに stamp しない(正規化後にまたユニークになるのを防ぐ)")
    void stampIfEligibleSkipsEmptyThreads() {
        ItemFactory itemFactory = mock(ItemFactory.class);
        PickupQualityListener listener = new PickupQualityListener(
                MockBukkit.createMockPlugin(),
                itemFactory,
                mock(com.trinityforge.config.domains.ItemStatsConfig.class),
                mock(com.trinityforge.config.domains.QualityTiersConfig.class),
                mock(com.trinityforge.config.domains.QualityConfig.class),
                catalogWithEmptyThread(),
                new PlayerLootLuckSource(java.util.logging.Logger.getLogger("test"), null));
        ItemStack stack = uniqueEmptyThread(99L, 2, "個体");
        org.mockbukkit.mockbukkit.entity.PlayerMock player =
                org.mockbukkit.mockbukkit.MockBukkit.getMock().addPlayer();
        player.getInventory().setItem(0, stack);

        listener.sweepInventory(player);
        verify(itemFactory, never()).stamp(any(ItemStack.class), anyLong(), anyInt());
    }
}
