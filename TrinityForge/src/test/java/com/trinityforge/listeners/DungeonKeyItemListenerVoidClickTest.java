package com.trinityforge.listeners;

import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.DungeonGateConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.mobs.DungeonEntryGui;
import com.trinityforge.mobs.DungeonGateService;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.ItemFactory;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回帰テスト(2026-08-03、実サーバ報告「ダンジョンの鍵を虚空にクリックしても使えない」):
 * ブロックに向けていない右クリック({@code RIGHT_CLICK_AIR})でも潜入確認GUIが開くこと。
 *
 * <p>真因は {@link DungeonKeyItemListener#onInteract} に付いていた {@code ignoreCancelled = true}。
 * {@link PlayerInteractEvent} はクリックしたブロックが {@code null} だとコンストラクタが
 * {@code useClickedBlock = DENY} と初期化し、{@code isCancelled()} はその値を見るだけなので、
 * <b>空クリックは誰もキャンセルしていなくても生成時点から「キャンセル済み」</b>になる。
 */
class DungeonKeyItemListenerVoidClickTest {

    private static final String KEY_ID = "key_mines";
    private static final Logger LOG = Logger.getLogger("DungeonKeyItemListenerVoidClickTest");

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
            case "getLogger" -> LOG;
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

    private static ItemStack keyStack() {
        ItemStack stack = new ItemStack(Material.TRIAL_KEY);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(KEY_ID);
        stack.setItemMeta(meta);
        return stack;
    }

    private DungeonKeyItemListener buildListener(File dir) throws Exception {
        File file = new File(dir, DungeonGateConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), """
                gates:
                  em_id_the_mines:
                    content-package: the_mines_dungeon
                    required-combat-level: 0
                    key-item: %s
                    key-amount: 1
                """.formatted(KEY_ID));
        Plugin plugin = fakePlugin(dir);
        DungeonGateConfig gateConfig = new DungeonGateConfig();
        gateConfig.load(plugin);

        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        CrossPluginItemResolver resolver = new CrossPluginItemResolver(catalog, mock(ItemFactory.class));
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(50);

        DungeonGateService gateService = new DungeonGateService(gateConfig, combat, resolver);
        DungeonEntryGui gui = new DungeonEntryGui(plugin, gateService, combat);
        return new DungeonKeyItemListener(gateConfig, gateService.keyMatcher(), gui);
    }

    @Test
    @DisplayName("虚空(ブロックに向いていない)への右クリックでも潜入確認GUIが開く")
    void rightClickAirOpensConfirmGui(@org.junit.jupiter.api.io.TempDir File dir) throws Exception {
        DungeonKeyItemListener listener = buildListener(dir);
        PlayerMock player = server.addPlayer();
        ItemStack key = keyStack();
        player.getInventory().setItemInMainHand(key);

        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_AIR, key, null, BlockFace.SELF, EquipmentSlot.HAND);
        // 罠そのものを固定する: 誰もキャンセルしていないのに、生成直後から isCancelled() == true。
        assertTrue(event.isCancelled(),
                "RIGHT_CLICK_AIR は blockClicked == null なので useClickedBlock が DENY 初期化される");

        listener.onInteract(event);

        assertEquals(Event.Result.DENY, event.useItemInHand(),
                "リスナーが処理してイベントをキャンセルしたはず(=空クリックが配送されている)");
        assertNotSame(player.getInventory(), player.getOpenInventory().getTopInventory(),
                "潜入確認GUIが実際に開いていること");
        assertEquals(27, player.getOpenInventory().getTopInventory().getSize());
    }

    @Test
    @DisplayName("onInteract に ignoreCancelled=true を付け直すと RIGHT_CLICK_AIR が届かなくなるため禁止")
    void onInteractMustNotIgnoreCancelled() throws NoSuchMethodException {
        EventHandler annotation = DungeonKeyItemListener.class
                .getMethod("onInteract", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);

        assertFalse(annotation.ignoreCancelled(),
                "PlayerInteractEvent は RIGHT_CLICK_AIR のとき常に isCancelled()==true なので、"
                        + "ignoreCancelled=true を付けると Bukkit のイベントバスが空クリックを配送しない。"
                        + "キャンセル判定は useItemInHand()==DENY で行うこと");
    }
}
