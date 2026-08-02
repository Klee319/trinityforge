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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
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
 * 回帰テスト(2026-08-02、コーディネーター指摘反映版):
 * <ul>
 *   <li><b>本質(コーディネーター指摘の核心)</b>: 虚空右クリックフォールバック
 *       ({@link VoidRightClickBridge.Handler#tryHandle}) は左クリック(空振り攻撃)との誤検出が
 *       ありうるため、<b>その経路だけでは券が絶対に消費されない</b>こと(確認GUIが開くだけ)。</li>
 *   <li>確認GUIで「使用する」を実際にクリックした場合にのみ、通常の {@code onInteract} 経路と
 *       同じ抽選/消費結果になること。</li>
 *   <li>確認GUIで「やめる」を押した/GUIを無視した場合は券が一切減らないこと。</li>
 *   <li>当選メッセージの {@code <item>} プレースホルダが生ID(例 {@code thread_luck})ではなく
 *       アイテムの表示名を出すこと。解決できない場合は生IDへフォールバックすること(要件#34)。</li>
 * </ul>
 */
class GachaListenerVoidClickAndDisplayNameTest {

    private static final String TICKET_CATALOG_ID = "test_gacha_ticket";
    private static final String PRIZE_CATALOG_ID = "test_prize_item";
    private static final String POOL_ID = "test_pool";

    // GachaListener の CONFIRM_YES_SLOT/CONFIRM_NO_SLOT と一致させる(private定数のためテスト側で複製)。
    private static final int CONFIRM_YES_SLOT = 4;
    private static final int CONFIRM_NO_SLOT = 6;

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

    private static InventoryClickEvent click(PlayerMock player, int slot) {
        return new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    @Test
    @DisplayName("虚空右クリック(tryHandle)は確認GUIを開くだけで、その場では券を1枚も消費しない"
            + "(=左クリック空振りとの誤検出時の実害をゼロにする、コーディネーター指摘の本質)")
    void tryHandleAloneNeverConsumesTicket(@org.junit.jupiter.api.io.TempDir File dir) throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.create(any(ItemTemplate.class), anyLong(), anyInt())).thenReturn(namedPrizeStack());
        GachaListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        ItemStack ticket = ticketStack();
        player.getInventory().setItemInMainHand(ticket);
        drainLastMessage(player);

        boolean handled = listener.tryHandle(player, player.getInventory().getItemInMainHand());

        assertTrue(handled, "登録済みの券なので tryHandle は true を返すはず"
                + "(=VoidRightClickBridge が『処理済み』と判断できる)");
        assertEquals(3, player.getInventory().getItemInMainHand().getAmount(),
                "確認GUIを開いただけの段階では券は1枚も減らない");
        assertFalse(player.getInventory().first(Material.DIAMOND) >= 0,
                "確認前に景品が付与されてはいけない");
        Inventory top = player.getOpenInventory().getTopInventory();
        assertTrue(top.getSize() > 0 && top != player.getInventory(),
                "確認GUIが実際に開いていること");
    }

    @Test
    @DisplayName("確認GUIで『使用する』をクリックして初めて1枚消費・景品付与・表示名メッセージが確定する")
    void confirmingInGuiActuallyDrawsAndConsumesOneTicket(@org.junit.jupiter.api.io.TempDir File dir)
            throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.create(any(ItemTemplate.class), anyLong(), anyInt())).thenReturn(namedPrizeStack());
        GachaListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(ticketStack());
        drainLastMessage(player);

        listener.tryHandle(player, player.getInventory().getItemInMainHand());
        listener.onClick(click(player, CONFIRM_YES_SLOT));

        assertEquals(2, player.getInventory().getItemInMainHand().getAmount(),
                "確認クリック後は通常のonInteract経路と同じく1枚だけ消費されるはず");
        assertTrue(player.getInventory().first(Material.DIAMOND) >= 0, "景品が付与されているはず");
        String message = drainLastMessage(player);
        assertTrue(message != null && message.contains("光る宝石"),
                "表示名(光る宝石)が当選メッセージに出るはず: " + message);
        assertFalse(message != null && message.contains(PRIZE_CATALOG_ID),
                "生ID(" + PRIZE_CATALOG_ID + ")がそのままチャットに出てはいけない(要件#34): " + message);
    }

    @Test
    @DisplayName("確認GUIで『やめる』を押した場合は券が一切減らない")
    void cancellingInGuiLeavesTicketUntouched(@org.junit.jupiter.api.io.TempDir File dir) throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.create(any(ItemTemplate.class), anyLong(), anyInt())).thenReturn(namedPrizeStack());
        GachaListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(ticketStack());

        listener.tryHandle(player, player.getInventory().getItemInMainHand());
        listener.onClick(click(player, CONFIRM_NO_SLOT));

        assertEquals(3, player.getInventory().getItemInMainHand().getAmount(),
                "『やめる』を押しても券は減らない");
        assertFalse(player.getInventory().first(Material.DIAMOND) >= 0, "景品も付与されない");
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

        listener.tryHandle(player, player.getInventory().getItemInMainHand());
        listener.onClick(click(player, CONFIRM_YES_SLOT));

        String message = drainLastMessage(player);
        assertFalse(message != null && message.contains(PRIZE_CATALOG_ID),
                "表示名が無くても生ID(" + PRIZE_CATALOG_ID + ")をそのまま出してはいけない"
                        + "(CollectionEntryNames.itemName が Material 翻訳キーへ落とす): " + message);
    }
}
