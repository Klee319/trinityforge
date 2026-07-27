package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogIdentityTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

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
    void newlyAdoptedCmdItemReceivesCatalogDisplayName() {
        ItemTemplate template = template();
        ItemCatalogConfig catalog = catalogWith(template);
        ItemStack stack = cmdStack("tf_core_meat");

        assertTrue(CatalogIdentity.ensure(stack, catalog));

        assertEquals("コアミート", PLAIN.serialize(stack.getItemMeta().displayName()));
        assertEquals(template.id(), ItemData.of(stack.getItemMeta()).catalogId().orElseThrow());
    }

    @Test
    void existingMachineIdNameIsRepaired() {
        ItemTemplate template = template();
        ItemCatalogConfig catalog = catalogWith(template);
        ItemStack stack = cmdStack("tf_core_meat");
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setCatalogId(template.id());
        data.setBindType(template.bindType());
        stack.setItemMeta(meta);

        assertTrue(CatalogIdentity.ensure(stack, catalog));

        assertEquals("コアミート", PLAIN.serialize(stack.getItemMeta().displayName()));
    }

    @Test
    void anvilRenameIsPreservedAfterCatalogIdentityWasStamped() {
        ItemTemplate template = template();
        ItemCatalogConfig catalog = catalogWith(template);
        ItemStack stack = cmdStack("俺のコアミート");
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setCatalogId(template.id());
        data.setBindType(template.bindType());
        stack.setItemMeta(meta);

        assertFalse(CatalogIdentity.ensure(stack, catalog));

        assertEquals("俺のコアミート", PLAIN.serialize(stack.getItemMeta().displayName()));
    }

    private static ItemTemplate template() {
        return new ItemTemplate("tf_core_meat", Material.LEATHER,
                "<gold>コアミート</gold>", 5103,
                BindType.TRADEABLE, 0, null);
    }

    private static ItemCatalogConfig catalogWith(ItemTemplate template) {
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.all()).thenReturn(Map.of(template.id(), template));
        return catalog;
    }

    @SuppressWarnings("deprecation")
    private static ItemStack cmdStack(String name) {
        ItemStack stack = new ItemStack(Material.LEATHER);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(5103);
        meta.displayName(Component.text(name));
        stack.setItemMeta(meta);
        return stack;
    }
}
