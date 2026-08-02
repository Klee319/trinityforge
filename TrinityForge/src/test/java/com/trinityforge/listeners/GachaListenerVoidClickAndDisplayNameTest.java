package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.GachaConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回帰テスト:
 * <ul>
 *   <li><b>虚空(何も無い方向)への右クリックでガチャ券が使えること</b>(2026-08-03、実サーバ報告)。
 *       真因は {@link GachaListener#onInteract} に付いていた {@code ignoreCancelled = true} で、
 *       {@link PlayerInteractEvent} は「クリックしたブロックが {@code null}」だと生成時点から
 *       {@code isCancelled() == true} になる({@code useClickedBlock} が {@code DENY} 初期化される)ため、
 *       {@code RIGHT_CLICK_AIR} が購読者へ一切配送されていなかった。
 *       ブロックに向けた右クリックだけが動いていたのはこのため。</li>
 *   <li>当選メッセージの {@code <item>} プレースホルダが生ID(例 {@code thread_luck})ではなく
 *       アイテムの表示名を出すこと。解決できない場合は生IDへフォールバックすること(要件#34)。</li>
 * </ul>
 *
 * <p>2026-08-02 に足した腕振り経由のフォールバック({@code VoidRightClickBridge})は、上の真因が
 * 判明したため撤去した。当時この位置にあった「フォールバック経路単体では券を消費しない」テストも
 * 対象ごと消えている。
 */
class GachaListenerVoidClickAndDisplayNameTest {

    private static final String TICKET_CATALOG_ID = "test_gacha_ticket";
    private static final String PRIZE_CATALOG_ID = "test_prize_item";
    private static final String POOL_ID = "test_pool";

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("GachaListenerVoidClickAndDisplayNameTest");
            case "saveResource" -> null;
            case "getName" -> "TrinityForge";
            case "namespace" -> "trinityforge"; // NamespacedKey(Plugin,String) は getName() でなく
                                                 // Plugin#namespace() を直接呼ぶ(Namespaced継承分)
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }

    private static ItemCatalogConfig loadCatalog(File dir) throws IOException {
        File file = new File(dir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), """
                items:
                  %s:
                    material: DIAMOND
                """.formatted(PRIZE_CATALOG_ID));
        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static GachaConfig loadGacha(File dir) throws IOException {
        File file = new File(dir, GachaConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), """
                pools:
                  %s:
                    entries:
                      - item: "%s"
                        weight: 1
                tickets:
                  %s:
                    pool: %s
                """.formatted(POOL_ID, PRIZE_CATALOG_ID, TICKET_CATALOG_ID, POOL_ID));
        GachaConfig config = new GachaConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static ItemStack ticketStack() {
        ItemStack stack = new ItemStack(Material.PAPER, 3);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(TICKET_CATALOG_ID);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 表示名付きの景品スタック(実際の ItemFactory の代わりにモックで返す)。 */
    private static ItemStack namedPrizeStack() {
        ItemStack stack = new ItemStack(Material.DIAMOND);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("光る宝石"));
        stack.setItemMeta(meta);
        return stack;
    }

    private static String drainLastMessage(PlayerMock player) {
        String message = null;
        String next;
        while ((next = player.nextMessage()) != null) {
            message = next;
        }
        return message;
    }

    private GachaListener buildListener(File dir, ItemFactory itemFactory) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(dir);
        GachaConfig gacha = loadGacha(dir);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(any())).thenReturn(
                new PlayerCombatAggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
        return new GachaListener(fakePlugin(dir), gacha, catalog, itemFactory, mock(QualityConfig.class), aggregator);
    }

    /** 虚空への右クリック: クリックしたブロックが {@code null} の {@link PlayerInteractEvent}。 */
    private static PlayerInteractEvent rightClickAir(PlayerMock player) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                player.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND);
    }

    @Test
    @DisplayName("虚空への右クリック(RIGHT_CLICK_AIR)でも券が1枚消費され景品が付与される"
            + "(ブロックに向けたときだけ動いていた実サーバ報告の回帰)")
    void rightClickAirDrawsAndConsumesOneTicket(@org.junit.jupiter.api.io.TempDir File dir) throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.create(any(ItemTemplate.class), anyLong(), anyInt())).thenReturn(namedPrizeStack());
        GachaListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(ticketStack());
        drainLastMessage(player);

        PlayerInteractEvent event = rightClickAir(player);
        // 罠そのものを固定する: 空クリックのイベントは誰もキャンセルしていないのに
        // 生成直後から isCancelled() == true になっている。
        assertTrue(event.isCancelled(),
                "RIGHT_CLICK_AIR は blockClicked == null なので useClickedBlock が DENY 初期化され、"
                        + "生成時点で isCancelled() == true になる(Bukkit の仕様)");

        listener.onInteract(event);

        assertEquals(2, player.getInventory().getItemInMainHand().getAmount(),
                "虚空右クリックでも券は1枚だけ消費されるはず");
        assertTrue(player.getInventory().first(Material.DIAMOND) >= 0, "景品が付与されているはず");
    }

    @Test
    @DisplayName("onInteract に ignoreCancelled=true を付け直すと RIGHT_CLICK_AIR が届かなくなるため禁止")
    void onInteractMustNotIgnoreCancelled() throws NoSuchMethodException {
        EventHandler annotation = GachaListener.class
                .getMethod("onInteract", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);

        assertFalse(annotation.ignoreCancelled(),
                "PlayerInteractEvent は RIGHT_CLICK_AIR のとき常に isCancelled()==true なので、"
                        + "ignoreCancelled=true を付けると Bukkit のイベントバスが空クリックを配送しない。"
                        + "キャンセル判定は useItemInHand()==DENY で行うこと");
    }

    @Test
    @DisplayName("他プラグインがアイテム使用を拒否した(useItemInHand=DENY)場合は券を消費しない")
    void deniedItemUseLeavesTicketUntouched(@org.junit.jupiter.api.io.TempDir File dir) throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.create(any(ItemTemplate.class), anyLong(), anyInt())).thenReturn(namedPrizeStack());
        GachaListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(ticketStack());

        PlayerInteractEvent event = rightClickAir(player);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        listener.onInteract(event);

        assertEquals(3, player.getInventory().getItemInMainHand().getAmount(),
                "アイテム使用が拒否されているので抽選も消費も起きない");
        assertFalse(player.getInventory().first(Material.DIAMOND) >= 0, "景品も付与されない");
    }

    @Test
    @DisplayName("当選メッセージは生IDでなく表示名を出す(要件#34)")
    void winMessageUsesDisplayName(@org.junit.jupiter.api.io.TempDir File dir) throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.create(any(ItemTemplate.class), anyLong(), anyInt())).thenReturn(namedPrizeStack());
        GachaListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(ticketStack());
        drainLastMessage(player);

        listener.onInteract(rightClickAir(player));

        String message = drainLastMessage(player);
        assertTrue(message != null && message.contains("光る宝石"),
                "表示名(光る宝石)が当選メッセージに出るはず: " + message);
        assertFalse(message != null && message.contains(PRIZE_CATALOG_ID),
                "生ID(" + PRIZE_CATALOG_ID + ")がそのままチャットに出てはいけない(要件#34): " + message);
    }

    @Test
    @DisplayName("景品に表示名が無いとき(素のMaterial)は生IDを出さずフォールバックする")
    void winMessageFallsBackWhenPrizeHasNoDisplayName(@org.junit.jupiter.api.io.TempDir File dir)
            throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.create(any(ItemTemplate.class), anyLong(), anyInt()))
                .thenReturn(new ItemStack(Material.DIAMOND));
        GachaListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(ticketStack());
        drainLastMessage(player);

        listener.onInteract(rightClickAir(player));

        String message = drainLastMessage(player);
        assertFalse(message != null && message.contains(PRIZE_CATALOG_ID),
                "表示名が無くても生ID(" + PRIZE_CATALOG_ID + ")をそのまま出してはいけない"
                        + "(CollectionEntryNames.itemName が Material 翻訳キーへ落とす): " + message);
    }
}
