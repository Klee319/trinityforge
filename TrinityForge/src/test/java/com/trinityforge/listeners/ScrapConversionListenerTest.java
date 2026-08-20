package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ScrapConversionListener} の右クリック消費フロー: 保持数不足時は不消費、十分な保持数なら
 * {@code base-amount} 分だけ消費して重み付き抽選の結果を1個渡す。抽選そのもの(weight解決)は
 * {@link com.trinityforge.config.domains.CraftingFeaturesConfigScrapConversionTest} が
 * {@code DisassemblyRule#pick} 経由で別途固定しているため、ここでは
 * 「候補が1件だけ」の設定にしてフロー(消費量・付与・キャンセル・メッセージ)だけを検証する。
 */
class ScrapConversionListenerTest {

    private static final String SOURCE_ID = "test_tf_scrap";
    private static final String PRIZE_CATALOG_ID = "test_scrap_prize";

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
            case "getLogger" -> Logger.getLogger("ScrapConversionListenerTest");
            case "saveResource" -> null;
            case "getName" -> "TrinityForge";
            case "namespace" -> "trinityforge";
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

    private static CraftingFeaturesConfig loadFeatures(File dir) throws IOException {
        File file = new File(dir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), """
                scrap-conversion:
                  %s:
                    base-amount: 4
                    outputs:
                      - item: "custom:%s"
                        weight: 1
                """.formatted(SOURCE_ID, PRIZE_CATALOG_ID));
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(dir)), "config must parse cleanly");
        return config;
    }

    private static ItemStack sourceStack(int amount) {
        ItemStack stack = new ItemStack(Material.IRON_NUGGET, amount);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(SOURCE_ID);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack namedPrizeStack() {
        ItemStack stack = new ItemStack(Material.DIAMOND);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("種別スクラップ"));
        stack.setItemMeta(meta);
        return stack;
    }

    private static PlayerInteractEvent rightClickAir(PlayerMock player) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                player.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND);
    }

    private static String drainLastMessage(PlayerMock player) {
        String message = null;
        String next;
        while ((next = player.nextMessage()) != null) {
            message = next;
        }
        return message;
    }

    private ScrapConversionListener buildListener(File dir, ItemFactory itemFactory) throws IOException {
        CraftingFeaturesConfig features = loadFeatures(dir);
        ItemCatalogConfig catalog = loadCatalog(dir);
        return new ScrapConversionListener(features, catalog, itemFactory);
    }

    @Test
    @DisplayName("4個以上保持していれば4個だけ消費して結果を1個付与する")
    void convertsExactlyFourAndGivesOnePrize(@TempDir File dir) throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.create(any(ItemTemplate.class), anyLong(), anyInt())).thenReturn(namedPrizeStack());
        ScrapConversionListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(sourceStack(6));

        listener.onInteract(rightClickAir(player));

        ItemStack remaining = player.getInventory().getItemInMainHand();
        assertEquals(Material.IRON_NUGGET, remaining.getType());
        assertEquals(2, remaining.getAmount(), "6個保持->4個消費で2個残るはず");
        assertTrue(player.getInventory().first(Material.DIAMOND) >= 0, "変換結果が付与されているはず");
    }

    @Test
    @DisplayName("保持数が base-amount 未満なら何も消費せず何も付与しない")
    void insufficientAmountConsumesNothing(@TempDir File dir) throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.create(any(ItemTemplate.class), anyLong(), anyInt())).thenReturn(namedPrizeStack());
        ScrapConversionListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(sourceStack(3));

        listener.onInteract(rightClickAir(player));

        ItemStack remaining = player.getInventory().getItemInMainHand();
        assertEquals(3, remaining.getAmount(), "4個未満なら消費されないはず");
        assertFalse(player.getInventory().first(Material.DIAMOND) >= 0, "結果も付与されないはず");
    }

    @Test
    @DisplayName("空クリック(RIGHT_CLICK_AIR)でも変換される — ignoreCancelled=true を付けてはいけない")
    void rightClickAirStillConverts(@TempDir File dir) throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.create(any(ItemTemplate.class), anyLong(), anyInt())).thenReturn(namedPrizeStack());
        ScrapConversionListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(sourceStack(4));

        PlayerInteractEvent event = rightClickAir(player);
        assertTrue(event.isCancelled(),
                "RIGHT_CLICK_AIR は blockClicked==null なので生成時点で isCancelled()==true(Bukkitの仕様)");

        listener.onInteract(event);

        // 4個ちょうど消費した後、addItem() が「今空いた main-hand スロット(通常は保持スロット=0)」を
        // 埋めることがある(MockBukkitでも実機と同じ挙動)。そのため「main-hand が空になる」ではなく
        // 「IRON_NUGGET が1つも残っていない/DIAMONDが付与されている」で検証する。
        assertEquals(-1, player.getInventory().first(Material.IRON_NUGGET),
                "4個ちょうどなら IRON_NUGGET は1つも残らないはず");
        assertTrue(player.getInventory().first(Material.DIAMOND) >= 0, "結果が付与されているはず");
    }

    @Test
    @DisplayName("onInteract に ignoreCancelled=true は付けない(GachaListenerと同じ罠)")
    void onInteractMustNotIgnoreCancelled() throws NoSuchMethodException {
        org.bukkit.event.EventHandler annotation = ScrapConversionListener.class
                .getMethod("onInteract", PlayerInteractEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        assertFalse(annotation.ignoreCancelled(),
                "PlayerInteractEvent は RIGHT_CLICK_AIR のとき常に isCancelled()==true なので、"
                        + "ignoreCancelled=true を付けると空クリックが配送されない");
    }

    @Test
    @DisplayName("他プラグインがアイテム使用を拒否した(useItemInHand=DENY)場合は変換しない")
    void deniedItemUseLeavesSourceUntouched(@TempDir File dir) throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.create(any(ItemTemplate.class), anyLong(), anyInt())).thenReturn(namedPrizeStack());
        ScrapConversionListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(sourceStack(4));

        PlayerInteractEvent event = rightClickAir(player);
        event.setUseItemInHand(Event.Result.DENY);
        listener.onInteract(event);

        assertEquals(4, player.getInventory().getItemInMainHand().getAmount(),
                "アイテム使用が拒否されているので変換も消費も起きない");
    }

    @Test
    @DisplayName("スクラップ変換と無関係のアイテムには反応しない")
    void unrelatedItemIsIgnored(@TempDir File dir) throws IOException {
        ItemFactory itemFactory = mock(ItemFactory.class);
        ScrapConversionListener listener = buildListener(dir, itemFactory);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_NUGGET, 4));

        listener.onInteract(rightClickAir(player));

        assertEquals(4, player.getInventory().getItemInMainHand().getAmount(),
                "scrap-conversion に登録されていないアイテムは無視されるはず");
    }
}
