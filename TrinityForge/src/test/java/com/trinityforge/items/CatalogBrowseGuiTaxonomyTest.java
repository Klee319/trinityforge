package com.trinityforge.items;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /tf catalog} の並びを<b>出荷 {@code items/catalog.yml} そのもの</b>で固定する（2026-08-05）。
 *
 * <h2>このテストが守るもの</h2>
 * 分類は設定エディタが書く {@code _editor:} 区画から読んでいる。エディタ側の書式が変わったり
 * 節ごと消えたりすると、<b>例外は出ずに「タブが空の一覧画面」になる</b>。一覧画面の壊れ方として
 * これが一番たちが悪い（動いているように見えるのに中身が無い）ので、
 * ①分類が実際に読めていること ②<b>1件も取りこぼしていないこと</b> の2点を実データで固定する。
 *
 * <p>②が重要なのは、並びを「小分類に列挙された ID」だけで作ると<b>許可リスト方式</b>になるからで、
 * エディタで新しく足したアイテムが分類に登録されるまで画面から黙って消える。実装は
 * どのタブにも属さない残りを「未分類」へ回収することでこれを防いでいる。
 */
class CatalogBrowseGuiTaxonomyTest {

    private static final String CATALOG = "src/main/resources/items/catalog.yml";

    /** 分類の下限。節が丸ごと消えた/読めなくなったことに気づくため。 */
    private static final int MIN_EXPECTED_CATEGORIES = 20;

    private ItemCatalogConfig catalog;
    private CatalogBrowseGui gui;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        catalog = loadShipped();
        ItemAssembler assembler = org.mockito.Mockito.mock(ItemAssembler.class);
        gui = new CatalogBrowseGui(MockBukkit.createMockPlugin(), catalog, new ItemFactory(assembler));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("出荷カタログから _editor の分類が実際に読める")
    void shippedCatalogTaxonomyIsParsed() {
        ItemCatalogConfig.CatalogTaxonomy tax = catalog.taxonomy();

        assertFalse(tax.tabOf().isEmpty(),
                "_editor.itemTabs が1件も読めていない。エディタの書式が変わると"
                        + "例外を出さずにタブが空の画面になるので、ここで気づく必要がある");

        int categories = tax.categories().values().stream().mapToInt(List::size).sum();
        assertTrue(categories >= MIN_EXPECTED_CATEGORIES,
                "_editor.categories の小分類が " + categories + " 件しか読めていない(期待: "
                        + MIN_EXPECTED_CATEGORIES + " 件以上)");

        // 実データに必ずある代表例。ラベルまで到達していることの確認。
        assertTrue(tax.categories().getOrDefault("weapon", List.of()).stream()
                        .anyMatch(c -> "剣".equals(c.label())),
                "weapon タブに「剣」の小分類が見当たらない: "
                        + tax.categories().getOrDefault("weapon", List.of()).stream()
                        .map(ItemCatalogConfig.CatalogTaxonomy.Category::label).toList());
    }

    @Test
    @DisplayName("カタログのアイテムは1件残らずどれかのタブに出る（未分類が受け皿）")
    void everyCatalogItemLandsInExactlyOneTab() {
        List<String> tabs = gui.tabs();
        assertFalse(tabs.isEmpty(), "タブが1つも出ていない");

        List<String> shown = new ArrayList<>();
        for (String tab : tabs) {
            for (CatalogBrowseGui.Entry entry : gui.entriesFor(tab)) {
                shown.add(entry.id());
            }
        }

        Set<String> missing = new HashSet<>(catalog.all().keySet());
        missing.removeAll(shown);
        assertTrue(missing.isEmpty(),
                "画面に出ないカタログアイテムがある(" + missing.size() + " 件)。"
                        + "分類に載っていないアイテムは「未分類」タブへ回収されなければならない: "
                        + missing.stream().sorted().limit(15).toList());

        assertEquals(shown.size(), new HashSet<>(shown).size(),
                "同じアイテムが複数のタブ/小分類に重複して出ている");
    }

    @Test
    @DisplayName("準備中(draft)のアイテムは画面に出ない")
    void draftItemsAreNotShown() {
        Set<String> drafts = catalog.draftIds();
        assertFalse(drafts.isEmpty(), "前提が崩れている: 準備中のアイテムが1件も無い");

        for (String tab : gui.tabs()) {
            for (CatalogBrowseGui.Entry entry : gui.entriesFor(tab)) {
                assertFalse(drafts.contains(entry.id()),
                        "準備中のアイテムが " + tab + " タブに出ている: " + entry.id());
            }
        }
    }

    // ---- 出荷 yml をそのまま load() させる(＝本番と同じ経路) ----------------------------------

    private static ItemCatalogConfig loadShipped() throws Exception {
        java.nio.file.Path source = java.nio.file.Path.of(CATALOG);
        assertTrue(Files.isRegularFile(source), "出荷カタログが見つからない: " + source.toAbsolutePath());

        File dataFolder = Files.createTempDirectory("catalog-browse").toFile();
        File target = new File(dataFolder, ItemCatalogConfig.PATH);
        Files.createDirectories(target.getParentFile().toPath());
        Files.copy(source, target.toPath());

        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(dataFolder));
        return config;
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CatalogBrowseGuiTaxonomyTest");
            case "saveResource" -> throw new AssertionError("既にファイルがあるのに saveResource が呼ばれた");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }
}
