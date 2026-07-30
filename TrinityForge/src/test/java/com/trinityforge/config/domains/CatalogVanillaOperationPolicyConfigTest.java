package com.trinityforge.config.domains;

import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogVanillaOperationPolicy;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogVanillaOperationPolicyConfigTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void everyBundledCatalogEntryHasCmdAndIsRecognizedByTheVanillaOperationGuard() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var stream = getClass().getResourceAsStream("/items/catalog.yml")) {
            assertNotNull(stream, "bundled items/catalog.yml");
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        ItemCatalogConfig.ParseResult parsed = ItemCatalogConfig.parse(
                yaml.getConfigurationSection("items"), Logger.getLogger(getClass().getName()));
        assertEquals(0, parsed.skipped(), "the bundled catalog must parse without skipped entries");

        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        Map<String, ItemTemplate> templates = parsed.templates();
        when(catalog.all()).thenReturn(templates);
        when(catalog.template(anyString())).thenAnswer(invocation ->
                java.util.Optional.ofNullable(templates.get(invocation.getArgument(0, String.class))));

        int placeableEntries = 0;
        for (ItemTemplate template : templates.values()) {
            assertNotNull(template.customModelData(),
                    () -> template.id() + " has no CMD and would be vanilla by policy");
            ItemStack stack = catalogStack(template);
            assertTrue(CatalogVanillaOperationPolicy.isCatalogItem(stack, catalog),
                    () -> template.id() + " escaped the shared catalog identity guard");
            ItemStack cmdOnly = new ItemStack(template.material());
            ItemMeta cmdOnlyMeta = cmdOnly.getItemMeta();
            cmdOnlyMeta.setCustomModelData(template.customModelData());
            cmdOnly.setItemMeta(cmdOnlyMeta);
            assertTrue(CatalogVanillaOperationPolicy.isCatalogItem(cmdOnly, catalog),
                    () -> template.id() + " escaped the material+CMD recovery guard");
            if (template.material().isBlock()) {
                placeableEntries++;
            }
        }

        ItemTemplate halo = templates.get("novus_criculus_luminis");
        assertNotNull(halo);
        assertEquals(Material.GLOWSTONE, halo.material());
        assertTrue(placeableEntries > 0, "the catalog must exercise the block-placement branch");
    }

    @Test
    void cmdLessStackRemainsVanillaEvenWhenStaleCatalogPdcExists() {
        ItemTemplate template = new ItemTemplate(
                "iron_dagger", Material.IRON_SWORD, "Iron Dagger", 5,
                com.trinityforge.pdc.BindType.TRADEABLE, 0, null);
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template("iron_dagger")).thenReturn(java.util.Optional.of(template));
        when(catalog.all()).thenReturn(Map.of(template.id(), template));

        ItemStack stack = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(template.id());
        stack.setItemMeta(meta);

        assertFalse(CatalogVanillaOperationPolicy.isCatalogItem(stack, catalog));
    }

    private static ItemStack catalogStack(ItemTemplate template) {
        ItemStack stack = new ItemStack(template.material());
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(template.customModelData());
        ItemData.of(meta).setCatalogId(template.id());
        stack.setItemMeta(meta);
        return stack;
    }
}
