package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
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
 * W-140(2026-08-19) 実サーバ報告の回帰ガード:
 * 「鍛冶台でネザライト化する際に素材を消費せず無限にアイテムをネザライト化できてしまう」。
 *
 * <p><b>真因</b>は 2026-07-28 のクラフト結果枠複製
 * ({@link CraftQualityListenerResultDupeTest}) と<b>同一機構</b>。CraftBukkit の
 * {@code handleContainerClick} は <b>イベント発火 → バニラの
 * {@code AbstractContainerMenu.clicked(...)}</b> の順で走るので、{@link SmithItemEvent} の
 * ハンドラ内で {@code player.setItemOnCursor(...)} を呼ぶと、続くバニラ処理は
 * 「カーソルが空 → 結果枠を取る」ではなく「カーソルに同じ品がある → マージする」経路へ入る。
 * 最大スタック 1 の装備では取得上限が {@code 1 - 1 = 0} になり
 * {@code Slot#tryRemove(count, maxStackSize - cursorCount)} が空を返すため、
 * <b>{@code ResultSlot#onTake} が呼ばれず素材(素材装備・ネザライトインゴット・テンプレート)が
 * 一切消費されない</b>のに、プレイヤーの手にはリスナーが載せた完成品が残る = サーバ側複製。
 *
 * <p>したがってこのテストの主眼は「{@link CatalogSmithingListener#onSmith} がカーソルに
 * 触らず、結果枠だけを差し替えること」。カーソルを書けばこのテストは落ちる。
 */
class CatalogSmithingListenerDupeTest {

    private static final String CATALOG_YAML = """
            items:
              diamond_bow:
                material: BOW
                display-name: ダイヤモンドの弓
                custom-model-data: 1096
              netherite_bow:
                material: BOW
                display-name: ネザライトの弓
                custom-model-data: 1097
                recipe:
                  method: netherite
                  source-item: custom:diamond_bow
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
            case "getLogger" -> Logger.getLogger("CatalogSmithingListenerDupeTest");
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

    /** {@code assemble(...)} を素通しにした mock（数値導出は別テストの責務）。 */
    private static ItemFactory factory() {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenReturn(0);
        return new ItemFactory(assembler);
    }

    /**
     * 鍛冶台の 3 スロットと結果枠を持つ {@link SmithItemEvent}。
     *
     * <p>{@code getWhoClicked()} を必ず stub しておく — 修正前のコードはここからプレイヤーを取って
     * {@code setItemOnCursor} を呼んでいたので、stub しないと「カーソルに触らない」検証が
     * 空振り（{@code UnsupportedOperationException} で落ちる別事象）になる。
     */
    private SmithItemEvent smithEvent(Player player, ItemStack template, ItemStack base,
                                      ItemStack addition, ItemStack current) {
        SmithingInventory inventory = mock(SmithingInventory.class);
        when(inventory.getInputTemplate()).thenReturn(template);
        when(inventory.getInputEquipment()).thenReturn(base);
        when(inventory.getInputMineral()).thenReturn(addition);

        SmithItemEvent event = mock(SmithItemEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getCurrentItem()).thenReturn(current);
        return event;
    }

    private static ItemStack netheriteTemplate() {
        return new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
    }

    private static ItemStack netheriteIngot() {
        return new ItemStack(Material.NETHERITE_INGOT);
    }

    @Test
    @DisplayName("カタログ品のネザライト化はカーソルに触らない(触るとバニラが素材を消費せず複製する)")
    void catalogSmithNeverWritesTheCursor(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemFactory itemFactory = factory();
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, itemFactory);
        ItemTemplate source = catalog.template("diamond_bow").orElseThrow();
        Player player = mock(Player.class);

        SmithItemEvent event = smithEvent(player, netheriteTemplate(),
                itemFactory.createIdentityOnly(source), netheriteIngot(),
                itemFactory.createIdentityOnly(catalog.template("netherite_bow").orElseThrow()));
        listener.onSmith(event);

        verify(player, never()).setItemOnCursor(any());
    }

    @Test
    @DisplayName("カタログ品のネザライト化は結果枠へ刻印済みを差し込む(バニラがそこから取る)")
    void catalogSmithStampsTheResultSlot(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemFactory itemFactory = factory();
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, itemFactory);
        ItemTemplate source = catalog.template("diamond_bow").orElseThrow();

        SmithItemEvent event = smithEvent(mock(Player.class), netheriteTemplate(),
                itemFactory.createIdentityOnly(source), netheriteIngot(),
                itemFactory.createIdentityOnly(catalog.template("netherite_bow").orElseThrow()));
        listener.onSmith(event);

        verify(event).setCurrentItem(any(ItemStack.class));
    }

    /**
     * W-51 の救済経路（CMD 無し・品質付きバニラ装備の再スタンプ）も同じ機構で複製する。
     * こちらはカタログ照合が外れる = {@code restampPlainQualitySmith} に落ちる側。
     */
    @Test
    @DisplayName("品質付きバニラ装備の再スタンプもカーソルに触らない")
    void plainQualitySmithNeverWritesTheCursor(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, factory());
        Player player = mock(Player.class);

        ItemStack base = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta baseMeta = base.getItemMeta();
        ItemData.of(baseMeta).setRollSeed(999L);
        ItemData.of(baseMeta).setQuality(5);
        base.setItemMeta(baseMeta);

        SmithItemEvent event = smithEvent(player, netheriteTemplate(), base, netheriteIngot(),
                new ItemStack(Material.NETHERITE_SWORD));
        listener.onSmith(event);

        verify(player, never()).setItemOnCursor(any());
        verify(event).setCurrentItem(any(ItemStack.class));
    }
}
