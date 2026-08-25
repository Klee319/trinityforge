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
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogVanillaOperationPolicyConfigTest {

    /**
     * かつて CMD 未割当を許していた3特殊アイテム
     * (role_reselect_ticket / stat_reroll_ticket / quality_upgrade_ticket)。
     *
     * <p>2026-08-09 に cmd-registry.json へ採番済み(10 / 16 / 5446)で、出荷 catalog.yml も
     * 3件とも custom-model-data を持っている。2026-08-25 に実物で確認して**例外を撤去した**。
     *
     * <p>⚠ ここを再び空でない集合に戻すと、その id は
     * {@link CatalogVanillaOperationPolicy} の「素材+CMD復元ガード」の検査から丸ごと外れる。
     * 許可リスト方式の検査はリスト自体が腐ると検査ごと無効になるので、
     * 一時例外を足すときは必ず追跡先(台帳の行番号)を書いて、外す条件を明記すること。
     */
    private static final Set<String> PENDING_CMD_ASSIGNMENT = Set.of();

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

        for (ItemTemplate template : templates.values()) {
            if (PENDING_CMD_ASSIGNMENT.contains(template.id())) {
                continue; // 意図的な一時的例外。理由はクラス冒頭の PENDING_CMD_ASSIGNMENT の javadoc参照。
            }
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
        }

        // 「素材がブロックでない TF 品も同じガードを通る」ことの代表例として輪転を1件だけ固定する。
        // 出荷 catalog.yml は 2026-08-25 時点で GLOWSTONE_DUST（かつては GLOWSTONE だった）。
        ItemTemplate halo = templates.get("novus_criculus_luminis");
        assertNotNull(halo);
        assertEquals(Material.GLOWSTONE_DUST, halo.material());
        // ブロック素材の分岐は【出荷 catalog.yml の中身に依存させない】。
        // 以前は「輪転がたまたま GLOWSTONE(=ブロック)だった」ことに寄りかかっていて、
        // 2026-08-25 に素材が GLOWSTONE_DUST へ変わった時点で出荷カタログの
        // ブロック素材エントリが 0 件になり、検査が実物ではなく偶然で赤くなった。
        ItemTemplate placeable = new ItemTemplate(
                "test_placeable_block", Material.GLOWSTONE, "Placeable", 4242,
                com.trinityforge.pdc.BindType.TRADEABLE, 0, null);
        assertTrue(placeable.material().isBlock(), "the fixture must be a block material");
        ItemCatalogConfig placeableCatalog = mock(ItemCatalogConfig.class);
        when(placeableCatalog.all()).thenReturn(Map.of(placeable.id(), placeable));
        when(placeableCatalog.template(anyString())).thenAnswer(invocation ->
                java.util.Optional.ofNullable(
                        Map.of(placeable.id(), placeable).get(invocation.getArgument(0, String.class))));
        assertTrue(CatalogVanillaOperationPolicy.isCatalogItem(catalogStack(placeable), placeableCatalog),
                "a block-material catalog item escaped the shared catalog identity guard");
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
