package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.Material;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogAnvilListenerTest {

    private static final String CATALOG_YAML = """
            items:
              source_blade:
                material: DIAMOND_SWORD
                display-name: 元
                custom-model-data: 101
              ember_core:
                material: AMETHYST_SHARD
                display-name: 触媒
                custom-model-data: 102
              fused_blade:
                material: NETHERITE_SWORD
                display-name: 合成先
                custom-model-data: 103
                recipe:
                  method: combine
                  source-item: custom:source_blade
                  addition-item: custom:ember_core
            """;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CatalogAnvilListenerTest");
            case "saveResource" -> throw new AssertionError("file exists; saveResource must not be called");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static ItemCatalogConfig loadCatalog(File tempDir) throws IOException {
        File file = new File(tempDir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), CATALOG_YAML);
        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void combinePreviewKeepsSourceQualityAndRollSeed(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemAssembler assembler = Mockito.mock(ItemAssembler.class);
        Mockito.when(assembler.assemble(
                        ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt()))
                .thenReturn(0);
        ItemFactory factory = new ItemFactory(assembler);
        CatalogAnvilListener listener = new CatalogAnvilListener(fakePlugin(tempDir), catalog, factory);

        ItemTemplate source = catalog.template("source_blade").orElseThrow();
        ItemTemplate addition = catalog.template("ember_core").orElseThrow();
        ItemStack left = factory.createIdentityOnly(source);
        left.editMeta(meta -> {
            ItemData data = ItemData.of(meta);
            data.setRollSeed(42L);
            data.setQuality(11);
        });
        ItemStack right = factory.createIdentityOnly(addition);
        right.editMeta(meta -> {
            ItemData data = ItemData.of(meta);
            data.setRollSeed(1L);
            data.setQuality(0);
        });

        AnvilInventory inventory = mock(AnvilInventory.class);
        when(inventory.getFirstItem()).thenReturn(left);
        when(inventory.getSecondItem()).thenReturn(right);
        PrepareAnvilEvent event = mock(PrepareAnvilEvent.class);
        when(event.getInventory()).thenReturn(inventory);

        listener.onPrepare(event);

        ArgumentCaptor<Long> seedCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> qualityCaptor = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(assembler, Mockito.atLeastOnce()).assemble(
                ArgumentMatchers.any(), ArgumentMatchers.any(),
                seedCaptor.capture(), qualityCaptor.capture());
        assertEquals(42L, seedCaptor.getValue(), "合成元のロールを引き継いでいない");
        assertEquals(11, qualityCaptor.getValue(), "合成元の品質を引き継いでいない");
    }
}
