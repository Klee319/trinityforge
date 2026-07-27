package com.trinityforge.progression;

import com.trinityforge.config.domains.CollectionConfig;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure grouping/paging logic for the {@code /tf collection} GUI (2026-07-23-stat-gate-overhaul §6.3). */
class CollectionGuiModelTest {

    private static final Logger LOG = Logger.getLogger("CollectionGuiModelTest");

    private static CollectionConfig configOf(File dir, String yaml) throws IOException {
        File file = new File(dir, CollectionConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        CollectionConfig config = new CollectionConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> LOG;
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    @Test
    void buildsConfiguredTabsPlusOtherWhenUncategorizedOwned(@TempDir File dir) throws Exception {
        CollectionConfig config = configOf(dir, """
                categories:
                  items:
                    weapons:
                      display-name: "武器"
                      order: 1
                      entries: ["iron_blade"]
                """);
        List<CollectionRecord> owned = List.of(
                new CollectionRecord("item:iron_blade", 1L, 10),
                new CollectionRecord("item:mystery_gem", 2L, 20));

        List<CollectionGuiModel.Tab> tabs = CollectionGuiModel.buildTabs(config, owned);
        assertEquals(2, tabs.size(), "武器タブ + その他タブ(mob側は未設定かつ無所持なので生成されない)");
        assertEquals("weapons", tabs.get(0).id());
        assertEquals(CollectionGuiModel.OTHER_TAB_ID, tabs.get(1).id());
        assertEquals(List.of("item:mystery_gem"), tabs.get(1).entries());
    }

    @Test
    void noCategoriesAndNoOwnedEntriesYieldsNoTabsForThatDomain(@TempDir File dir) throws Exception {
        CollectionConfig config = configOf(dir, "enabled: true");
        assertTrue(CollectionGuiModel.buildTabs(config, List.of()).isEmpty());
    }

    @Test
    void entriesForMarksUndiscoveredWhenNotOwned(@TempDir File dir) throws Exception {
        CollectionConfig config = configOf(dir, """
                categories:
                  items:
                    weapons:
                      order: 1
                      entries: ["iron_blade", "gold_blade"]
                """);
        List<CollectionRecord> owned = List.of(new CollectionRecord("item:iron_blade", 5L, 33));
        CollectionGuiModel.Tab tab = CollectionGuiModel.buildTabs(config, owned).get(0);
        List<CollectionGuiModel.GuiEntry> entries = CollectionGuiModel.entriesFor(tab, owned);

        assertEquals(2, entries.size());
        assertTrue(entries.get(0).discovered());
        assertEquals(33, entries.get(0).qualityPt());
        assertFalse(entries.get(1).discovered());
        assertEquals(0, entries.get(1).qualityPt());
    }

    @Test
    void paginateSplitsIntoFixedSizePages() {
        List<CollectionGuiModel.GuiEntry> entries = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> new CollectionGuiModel.GuiEntry("item:" + i, true, 0L, 0))
                .toList();
        List<List<CollectionGuiModel.GuiEntry>> pages = CollectionGuiModel.paginate(entries, 4);
        assertEquals(3, pages.size());
        assertEquals(4, pages.get(0).size());
        assertEquals(4, pages.get(1).size());
        assertEquals(2, pages.get(2).size());
    }

    @Test
    void paginateEmptyListYieldsOneEmptyPage() {
        List<List<CollectionGuiModel.GuiEntry>> pages = CollectionGuiModel.paginate(List.of(), 4);
        assertEquals(1, pages.size());
        assertTrue(pages.get(0).isEmpty());
    }

    @Test
    void nonPositivePageSizeYieldsSinglePage() {
        List<CollectionGuiModel.GuiEntry> entries = List.of(
                new CollectionGuiModel.GuiEntry("item:a", true, 0L, 0),
                new CollectionGuiModel.GuiEntry("item:b", true, 0L, 0));
        List<List<CollectionGuiModel.GuiEntry>> pages = CollectionGuiModel.paginate(entries, 0);
        assertEquals(1, pages.size());
        assertEquals(2, pages.get(0).size());
    }
}
