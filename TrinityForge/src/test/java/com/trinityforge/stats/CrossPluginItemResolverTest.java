package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CrossPluginItemResolver} (2026-07-23 stat-gate-overhaul §1 緊急修正1): shared id→ItemStack /
 * ItemStack→id seam for ids that may live in either the TF catalog or ArsPaper's registry (materials
 * moved there on 2026-07-21: gacha tickets, scrap, crystal apple, core materials, compressed blocks, …).
 */
class CrossPluginItemResolverTest {

    private static final NamespacedKey ARS_CUSTOM_ITEM_ID = new NamespacedKey("arspaper", "custom_item_id");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void createResolvesCatalogTemplateFirst() {
        ItemTemplate template = new ItemTemplate("tf_core_meat", Material.LEATHER,
                "<gold>コアミート</gold>", 5103, com.trinityforge.pdc.BindType.TRADEABLE, 0, null);
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template("tf_core_meat")).thenReturn(Optional.of(template));
        ItemFactory factory = mock(ItemFactory.class);
        ItemStack builtStack = new ItemStack(Material.LEATHER);
        when(factory.create(template, 42L, 3)).thenReturn(builtStack);

        CrossPluginItemResolver resolver = new CrossPluginItemResolver(catalog, factory);
        Optional<ItemStack> result = resolver.create("tf_core_meat", 42L, 3);

        assertTrue(result.isPresent());
        assertEquals(builtStack, result.get());
    }

    @Test
    void createFallsBackToVanillaMaterialWhenNeitherCatalogNorArsResolve() {
        // ArsPaper plugin is absent in the MockBukkit environment, so ArsItemGiveBridge.create()
        // (and thus CrossPluginItemResolver's Ars branch) naturally no-ops here.
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template("DIAMOND")).thenReturn(Optional.empty());
        ItemFactory factory = mock(ItemFactory.class);

        CrossPluginItemResolver resolver = new CrossPluginItemResolver(catalog, factory);
        Optional<ItemStack> result = resolver.create("DIAMOND");

        assertTrue(result.isPresent());
        assertEquals(Material.DIAMOND, result.get().getType());
    }

    @Test
    void createReturnsEmptyForUnresolvableId() {
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template("not_a_real_id")).thenReturn(Optional.empty());
        ItemFactory factory = mock(ItemFactory.class);

        CrossPluginItemResolver resolver = new CrossPluginItemResolver(catalog, factory);

        assertEquals(Optional.empty(), resolver.create("not_a_real_id"));
    }

    @Test
    void idOfReadsTfCatalogIdFirst() {
        ItemStack stack = new ItemStack(Material.LEATHER);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId("tf_gacha_ticket_1");
        stack.setItemMeta(meta);

        assertEquals(Optional.of("tf_gacha_ticket_1"), CrossPluginItemResolver.idOf(stack));
    }

    @Test
    void idOfFallsBackToArsCustomItemIdPdc() {
        // Simulates an item whose id now lives only in ArsPaper's materials.yml (moved 2026-07-21):
        // no TF ITEM_CATALOG_ID PDC, only the Ars arspaper:custom_item_id tag.
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(ARS_CUSTOM_ITEM_ID, PersistentDataType.STRING, "tf_scrap");
        stack.setItemMeta(meta);

        assertEquals(Optional.of("tf_scrap"), CrossPluginItemResolver.idOf(stack));
    }

    @Test
    void idOfIsEmptyForPlainVanillaItem() {
        ItemStack stack = new ItemStack(Material.DIAMOND);
        assertEquals(Optional.empty(), CrossPluginItemResolver.idOf(stack));
    }
}
