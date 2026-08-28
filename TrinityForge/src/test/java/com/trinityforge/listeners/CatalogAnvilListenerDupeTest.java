package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 金床 combine の増殖回帰。真因は {@link CatalogSmithingListenerDupeTest} と同型:
 * ハンドラ内で {@code player.setItemOnCursor(...)} を呼ぶとバニラが素材を消費せず複製する。
 */
class CatalogAnvilListenerDupeTest {

    private static final String CATALOG_YAML = """
            items:
              iron_guard:
                material: IRON_SWORD
                display-name: 鉄の守り
                custom-model-data: 2101
              upgrade_stone:
                material: AMETHYST_SHARD
                display-name: 強化石
                custom-model-data: 2102
              combined_guard:
                material: DIAMOND_SWORD
                display-name: 合成の守り
                custom-model-data: 2103
                recipe:
                  method: combine
                  source-item: custom:iron_guard
                  addition-item: custom:upgrade_stone
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
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenReturn(mock(BukkitTask.class));
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        when(server.getScheduler()).thenReturn(scheduler);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CatalogAnvilListenerDupeTest");
            case "getServer" -> server;
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

    private static ItemFactory factory() {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenReturn(0);
        return new ItemFactory(assembler);
    }

    private InventoryClickEvent takeEvent(Player player, ItemStack first, ItemStack second, ItemStack current) {
        AnvilInventory inventory = mock(AnvilInventory.class);
        when(inventory.getFirstItem()).thenReturn(first);
        when(inventory.getSecondItem()).thenReturn(second);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getRawSlot()).thenReturn(2);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getCurrentItem()).thenReturn(current);
        when(event.isShiftClick()).thenReturn(false);
        return event;
    }

    @Test
    @DisplayName("カタログ combine はカーソルに触らない(触るとバニラが素材を消費せず複製する)")
    void catalogCombineNeverWritesTheCursor(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemFactory itemFactory = factory();
        CatalogAnvilListener listener = new CatalogAnvilListener(fakePlugin(tempDir), catalog, itemFactory);
        ItemTemplate source = catalog.template("iron_guard").orElseThrow();
        ItemTemplate addition = catalog.template("upgrade_stone").orElseThrow();
        Player player = mock(Player.class);

        InventoryClickEvent event = takeEvent(player,
                itemFactory.createIdentityOnly(source),
                itemFactory.createIdentityOnly(addition),
                itemFactory.createIdentityOnly(catalog.template("combined_guard").orElseThrow()));
        listener.onTakeResult(event);

        verify(player, never()).setItemOnCursor(any());
        verify(event).setCurrentItem(any(ItemStack.class));
    }
}
