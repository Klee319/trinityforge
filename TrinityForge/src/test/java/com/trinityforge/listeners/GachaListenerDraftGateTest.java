package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.GachaConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemFactory;
import org.bukkit.Material;
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
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 敵対的レビュー指摘2(2026-08-02)の回帰ガード: {@code custom:} 接頭辞つきの draft 景品IDが
 * {@link GachaListener}(private {@code withoutDraftPrizes})の抽選前フィルタをすり抜けないこと。
 *
 * <h2>何が壊れていたか</h2>
 * {@code ItemCatalogConfig#isDraft} が生IDでしか判定せず、editorが正規化する
 * {@code custom:<id>} 形式(例: {@code gacha.yml} の {@code item: custom:test_draft_prize})を
 * 剥がしていなかった。すり抜けると景品は「準備中」ではなく「解決失敗」として扱われ、
 * {@code GachaListener} の「景品ロスト防止」フェイルセーフ(解決できない景品は券を消費しない)に
 * 乗ってしまう。
 *
 * <h2>なぜチャットメッセージで区別できるのか</h2>
 * 単一エントリのプールでは「券が消費されない」という結果自体は修正前後で変わらない
 * (フィルタで空になっても、フィルタをすり抜けて解決失敗しても、どちらも未消費)。
 * しかし通るコードパスが違う: フィルタが効けば
 * {@code pool.get().entries().isEmpty()} の専用早期リターン(「このガチャは現在準備中です」)を
 * 通り、<b>抽選(RNG)にすら入らない</b>。フィルタが効かなければ通常どおり抽選まで進み、
 * 解決失敗の汎用メッセージ(「景品の生成に失敗しました」)に落ちる。この文言の違いが
 * 「フィルタが実際に効いたか」の決定的な証拠になる。
 */
class GachaListenerDraftGateTest {

    private static final String DRAFT_ITEM_ID = "test_draft_prize";
    private static final String TICKET_CATALOG_ID = "test_gacha_ticket";

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
            case "getLogger" -> Logger.getLogger("GachaListenerDraftGateTest");
            case "saveResource" -> null;
            case "getName" -> "TrinityForge"; // GachaListener が確認GUI用に NamespacedKey を作るため必須
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
                    draft: true   # 準備中: ゲーム側へ配線しない
                    material: DIAMOND
                """.formatted(DRAFT_ITEM_ID));
        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static GachaConfig loadGacha(File dir) throws IOException {
        File file = new File(dir, GachaConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), """
                pools:
                  test_pool:
                    entries:
                      - item: "custom:%s"
                        weight: 1
                tickets:
                  %s:
                    pool: test_pool
                """.formatted(DRAFT_ITEM_ID, TICKET_CATALOG_ID));
        GachaConfig config = new GachaConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static ItemStack ticketStack() {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(TICKET_CATALOG_ID);
        stack.setItemMeta(meta);
        return stack;
    }

    private static PlayerInteractEvent rightClickEvent(PlayerMock player, ItemStack held) {
        player.getInventory().setItemInMainHand(held);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        return event;
    }

    private static String drainLastMessage(PlayerMock player) {
        String message = null;
        String next;
        while ((next = player.nextMessage()) != null) {
            message = next;
        }
        return message;
    }

    @Test
    @DisplayName("custom: 接頭辞つきの draft 景品だけのプールは抽選前に空になり、専用メッセージで打ち切る"
            + "(=解決失敗の汎用フェイルセーフ経路を通らない)")
    void draftOnlyPoolWithCustomPrefixIsFilteredBeforeDraw(@TempDir File dir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(dir);
        GachaConfig gacha = loadGacha(dir);
        assertTrue(catalog.isDraft(DRAFT_ITEM_ID), "テスト前提: カタログ側は draft と認識している");

        GachaListener listener = new GachaListener(fakePlugin(dir), gacha, catalog,
                mock(ItemFactory.class), mock(QualityConfig.class), mock(PlayerStatAggregator.class));
        PlayerMock player = server.addPlayer();
        ItemStack ticket = ticketStack();
        ticket.setAmount(3);
        PlayerInteractEvent event = rightClickEvent(player, ticket);
        drainLastMessage(player);

        listener.onInteract(event);

        assertEquals(3, player.getInventory().getItemInMainHand().getAmount(),
                "券が消費されている(draft 景品が抽選に混ざった)");
        String message = drainLastMessage(player);
        assertTrue(message != null && message.contains("準備中"),
                "抽選前フィルタの専用メッセージが出ていない(すり抜けて解決失敗の汎用メッセージに"
                        + "落ちている可能性がある): " + message);
    }
}
