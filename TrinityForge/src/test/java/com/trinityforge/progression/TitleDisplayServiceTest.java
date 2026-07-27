package com.trinityforge.progression;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B1 (2026-07-25 バグ報告): 称号(頭上表示)のオフセットconfig化 + despawn規律の回帰テスト。
 *
 * <p>{@link TitleDisplayService#spawn} は {@code TextDisplay#setBillboard(...)} を呼ぶが、これは
 * MockBukkit未実装({@code UnimplementedOperationException}, {@code DamagePopupDisplayTest} と同じ
 * 制約)。よって:
 * <ul>
 *   <li>「configから読まれること」は spawn() に到達する直前で offsetY を1回だけ評価する実装
 *       (本コミットでのリファクタ)を利用し、{@link #assertThrows} で spawn() 到達を確認しつつ
 *       供給された {@code DoubleSupplier} が呼ばれた回数を数える。</li>
 *   <li>「despawn規律」は実際にspawnを経由せず、private {@code active} マップへリフレクションで
 *       Mockito {@code mock(TextDisplay.class)} を直接注入し、各イベントハンドラがそれを
 *       確実に {@code remove()} してマップから外すことを検証する(スポーン経路のMockBukkit制約を回避)。</li>
 * </ul>
 */
class TitleDisplayServiceTest {

    private ServerMock server;
    private WorldMock world;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // --- config駆動オフセット ------------------------------------------------------------------------

    @Test
    void refreshReadsHeadOffsetFromConfiguredSupplier() {
        AtomicInteger reads = new AtomicInteger();
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "<red>Title</red>", () -> {
            reads.incrementAndGet();
            return 1.23;
        });
        Player player = server.addPlayer();

        // spawn()はTextDisplayのsetBillboard(未実装)で必ず例外化するが、それはoffsetY評価の"後"に
        // 到達する行なので、例外が飛ぶこと自体が spawn() まで進んだ証拠になる(DamagePopupDisplayTest と
        // 同じ論法)。
        assertThrows(UnimplementedOperationException.class, () -> service.refresh(player));
        assertEquals(1, reads.get(), "the configured head-offset-y supplier must be read exactly once per spawn");
    }

    @Test
    void resolveHeadOffsetYReturnsSuppliedValue() throws Exception {
        TitleDisplayService service = new TitleDisplayService(plugin, p -> null, () -> 2.5);
        assertEquals(2.5, invokeResolveHeadOffsetY(service), 1e-9);
    }

    @Test
    void resolveHeadOffsetYFallsBackWhenSupplierIsNonFinite() throws Exception {
        TitleDisplayService service = new TitleDisplayService(plugin, p -> null, () -> Double.NaN);
        double resolved = invokeResolveHeadOffsetY(service);
        assertTrue(Double.isFinite(resolved), "a non-finite supplier value must never reach the Transformation");
    }

    private static double invokeResolveHeadOffsetY(TitleDisplayService service) throws Exception {
        Method m = TitleDisplayService.class.getDeclaredMethod("resolveHeadOffsetY");
        m.setAccessible(true);
        return (double) m.invoke(service);
    }

    // --- despawn規律 (死亡/リスポーン/ワールド移動/ログアウト/テレポート) --------------------------------

    @SuppressWarnings("unchecked")
    private static Map<UUID, TextDisplay> activeMap(TitleDisplayService service) throws Exception {
        Field f = TitleDisplayService.class.getDeclaredField("active");
        f.setAccessible(true);
        return (Map<UUID, TextDisplay>) f.get(service);
    }

    private static TextDisplay injectActiveDisplay(TitleDisplayService service, UUID playerId) throws Exception {
        TextDisplay fake = mock(TextDisplay.class);
        when(fake.isValid()).thenReturn(true);
        activeMap(service).put(playerId, fake);
        return fake;
    }

    private TitleDisplayService serviceWithNoDisplay() {
        // textResolverがnullを返す = refresh()が呼ばれても何も出さない。despawn規律のテストは
        // active マップへ直接注入したフェイクの掃除だけを見るので、これで十分。
        return new TitleDisplayService(plugin, p -> null, () -> 0.75);
    }

    @Test
    void onQuitDespawnsAndRemovesTrackedDisplay() throws Exception {
        TitleDisplayService service = serviceWithNoDisplay();
        Player player = server.addPlayer();
        TextDisplay fake = injectActiveDisplay(service, player.getUniqueId());

        service.onQuit(new PlayerQuitEvent(player, "bye"));

        verify(fake, times(1)).remove();
        assertTrue(activeMap(service).isEmpty(), "the tracked display must be dropped from the active map");
    }

    @Test
    void onDeathDespawnsTrackedDisplay() throws Exception {
        TitleDisplayService service = serviceWithNoDisplay();
        Player player = server.addPlayer();
        TextDisplay fake = injectActiveDisplay(service, player.getUniqueId());

        DamageSource source = DamageSource.builder(DamageType.GENERIC).build();
        PlayerDeathEvent event = new PlayerDeathEvent(
                player, source, List.of(), 0, Component.text("died"), false);

        service.onDeath(event);

        verify(fake, times(1)).remove();
        assertTrue(activeMap(service).isEmpty());
    }

    @Test
    void onWorldChangeRefreshesAndDespawnsPreviousDisplay() throws Exception {
        TitleDisplayService service = serviceWithNoDisplay();
        Player player = server.addPlayer();
        TextDisplay fake = injectActiveDisplay(service, player.getUniqueId());

        service.onWorldChange(new PlayerChangedWorldEvent(player, world));

        // refresh() always despawns first (see javadoc); textResolver returns null here so nothing
        // new is spawned, leaving the map empty — the stale display must not linger.
        verify(fake, times(1)).remove();
        assertTrue(activeMap(service).isEmpty());
    }

    @Test
    void onTeleportSchedulesDespawnOfPreviousDisplayNextTick() throws Exception {
        TitleDisplayService service = serviceWithNoDisplay();
        Player player = server.addPlayer();
        TextDisplay fake = injectActiveDisplay(service, player.getUniqueId());

        Location from = player.getLocation();
        Location to = from.clone().add(5, 0, 5);
        service.onTeleport(new PlayerTeleportEvent(player, from, to));
        server.getScheduler().performOneTick();

        verify(fake, times(1)).remove();
        assertTrue(activeMap(service).isEmpty());
    }

    @Test
    void onRespawnSchedulesDespawnOfPreviousDisplayNextTick() throws Exception {
        TitleDisplayService service = serviceWithNoDisplay();
        Player player = server.addPlayer();
        TextDisplay fake = injectActiveDisplay(service, player.getUniqueId());

        service.onRespawn(new PlayerRespawnEvent(player, player.getLocation(), false));
        server.getScheduler().performOneTick();

        verify(fake, times(1)).remove();
        assertTrue(activeMap(service).isEmpty());
    }

    @Test
    void shutdownRemovesEveryTrackedDisplayAndClearsMap() throws Exception {
        TitleDisplayService service = serviceWithNoDisplay();
        Player p1 = server.addPlayer();
        Player p2 = server.addPlayer();
        TextDisplay fake1 = injectActiveDisplay(service, p1.getUniqueId());
        TextDisplay fake2 = injectActiveDisplay(service, p2.getUniqueId());

        service.shutdown();

        verify(fake1, times(1)).remove();
        verify(fake2, times(1)).remove();
        assertTrue(activeMap(service).isEmpty());
    }
}
