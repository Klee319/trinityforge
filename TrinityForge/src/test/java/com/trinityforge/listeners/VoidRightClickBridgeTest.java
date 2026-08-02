package com.trinityforge.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回帰テスト(2026-08-02): 実サーバ報告「ガチャ券/ダンジョンの鍵がブロックに向いてでないと使えない
 * (虚空右クリックで使えない)」の機構を固定する。
 *
 * <p>Minecraft クライアントは、vanilla の「使用」挙動を持たないアイテム(素の PAPER/TRIAL_KEY 等)で
 * 虚空(何も無い方向)へ右クリックすると {@link PlayerInteractEvent} 自体を発火させない
 * ({@link VoidRightClickBridge} javadoc 参照)。これは Bukkit/Paper のバグではなく vanilla の
 * ネットワーク層の制約なので、{@link VoidRightClickBridge} は {@link PlayerAnimationEvent}(腕振り)
 * を頼りに、次tickまでに「本物のイベント」(通常の {@link PlayerInteractEvent} か自分が攻撃者の
 * {@link EntityDamageByEntityEvent})が無かった場合に限りフォールバック処理を発火する。
 */
class VoidRightClickBridgeTest {

    private ServerMock server;
    private Plugin plugin;
    private VoidRightClickBridge bridge;
    private List<ItemStack> handled;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        bridge = new VoidRightClickBridge(plugin);
        server.getPluginManager().registerEvents(bridge, plugin);
        handled = new ArrayList<>();
        bridge.register((player, mainhand) -> {
            if (mainhand.getType() != Material.PAPER) {
                return false;
            }
            handled.add(mainhand);
            return true;
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("虚空(PlayerInteractEventが発火しない)へ右クリックしても、腕振りだけでハンドラへ合流する")
    void swingWithoutAnyInteractEventStillReachesHandler() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER));

        server.getPluginManager().callEvent(new PlayerAnimationEvent(player));
        server.getScheduler().performOneTick();

        assertEquals(1, handled.size(),
                "PlayerInteractEvent が一切発火しない虚空右クリックでもハンドラへ届くはず");
    }

    @Test
    @DisplayName("通常のPlayerInteractEventが同tick内に発火していれば、フォールバックは二重発火しない")
    void normalInteractEventSuppressesFallback() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER));

        // ブロックへ向けた通常の右クリック: 本物の PlayerInteractEvent が飛ぶケースを模す。
        // Mockito モックは HandlerList を持たないため PluginManager 経由の callEvent には流せない
        // (event.getHandlers() が null になり NPE)。bridge のハンドラを直接呼んで「本物イベントを
        // 観測済み」の状態だけを再現する(GachaListenerDraftGateTest と同じ直接呼び出しの流儀)。
        PlayerInteractEvent interact = mock(PlayerInteractEvent.class);
        when(interact.getPlayer()).thenReturn(player);
        when(interact.getHand()).thenReturn(EquipmentSlot.HAND);
        when(interact.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        bridge.onInteract(interact);
        server.getPluginManager().callEvent(new PlayerAnimationEvent(player));
        server.getScheduler().performOneTick();

        assertTrue(handled.isEmpty(),
                "通常経路で既に処理されているはずのスイングをフォールバックが二重処理してはいけない"
                        + "(券の二重消費に相当する)");
    }

    @Test
    @DisplayName("モブを攻撃した左クリックのswingは、フォールバックを誤発火させない")
    void attackSwingDoesNotMisfireFallback() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER));
        WorldMock world = server.addSimpleWorld("world");
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);

        server.getPluginManager().callEvent(new EntityDamageByEntityEvent(
                player, zombie, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0));
        server.getPluginManager().callEvent(new PlayerAnimationEvent(player));
        server.getScheduler().performOneTick();

        assertTrue(handled.isEmpty(),
                "紙(攻撃力を持たないアイテム)を持ったままモブを殴っただけで券を消費してはいけない");
    }

    @Test
    @DisplayName("対応しないアイテムを持ったスイングは何も起こさない")
    void swingWithUnrelatedItemIsIgnored() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIRT));

        server.getPluginManager().callEvent(new PlayerAnimationEvent(player));
        server.getScheduler().performOneTick();

        assertFalse(handled.stream().anyMatch(stack -> stack.getType() == Material.DIRT));
        assertTrue(handled.isEmpty());
    }
}
