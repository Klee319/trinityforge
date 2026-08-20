package com.trinityforge.mobs;

import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.DungeonGateConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.ItemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DungeonTeleporter}(2026-07-27, {@code DungeonEntryGui} からの抽出): 転送先の種類ごとに
 * 「onSuccess を正しいタイミングでだけ呼ぶか」を検証する。転送先の分岐ルール自体は
 * {@link DungeonEntryTargetResolverTest} が Bukkit 非依存で網羅しているため、ここでは Bukkit
 * (MockBukkit) を挟んだ実際の転送/後処理呼び出しの順序保証に絞る。EliteMobs連携委譲経路は
 * {@code EliteMobsDungeonBridge.isAvailable()} が MockBukkit 環境では常に false になる
 * (EliteMobsプラグイン自体を登録していないため)ため、失敗メッセージ経路のみ検証できる。
 */
class DungeonTeleporterTest {

    private ServerMock server;
    private DungeonGateService gateService;
    private DungeonTeleporter teleporter;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        DungeonGateConfig gateConfig = new DungeonGateConfig();
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        ItemFactory itemFactory = mock(ItemFactory.class);
        CrossPluginItemResolver itemResolver = new CrossPluginItemResolver(catalog, itemFactory);
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(0);
        gateService = new DungeonGateService(gateConfig, combat, itemResolver);
        teleporter = new DungeonTeleporter(gateService);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void worldSpawnTargetTeleportsAndRunsOnSuccess() {
        WorldMock world = server.addSimpleWorld("dungeon_crypt_world");
        DungeonGate gate = new DungeonGate("dungeon_crypt_world", List.of(), 0, null, 1);
        PlayerMock player = server.addPlayer();
        AtomicBoolean onSuccessRan = new AtomicBoolean(false);

        teleporter.proceedToTarget(player, gate, () -> onSuccessRan.set(true));

        assertTrue(onSuccessRan.get());
        assertEquals(world.getSpawnLocation().getWorld(), player.getLocation().getWorld());
    }

    @Test
    void explicitLocationTargetTeleportsAndRunsOnSuccess() {
        server.addSimpleWorld("instance_world");
        EntryLocation location = new EntryLocation("instance_world", 10.5, 70.0, -5.5, 0f, 0f);
        DungeonGate gate = new DungeonGate("dungeon_sanctum", List.of(), 0, null, 1, null, location);
        PlayerMock player = server.addPlayer();
        AtomicBoolean onSuccessRan = new AtomicBoolean(false);

        teleporter.proceedToTarget(player, gate, () -> onSuccessRan.set(true));

        assertTrue(onSuccessRan.get());
        assertEquals("instance_world", player.getLocation().getWorld().getName());
        assertEquals(10.5, player.getLocation().getX());
    }

    @Test
    void regionCenterTargetTeleportsAndRunsOnSuccess() {
        server.addSimpleWorld("field_world");
        GateRegion region = new GateRegion("field_world", -10, 0, -10, 10, 128, 10);
        DungeonGate gate = new DungeonGate("field_ruins", List.of(), 0, null, 1, region, null);
        PlayerMock player = server.addPlayer();
        AtomicBoolean onSuccessRan = new AtomicBoolean(false);

        teleporter.proceedToTarget(player, gate, () -> onSuccessRan.set(true));

        assertTrue(onSuccessRan.get());
        assertEquals("field_world", player.getLocation().getWorld().getName());
    }

    @Test
    void missingWorldDoesNotRunOnSuccess() {
        // "ghost_world" is never registered on the mock server.
        DungeonGate gate = new DungeonGate("ghost_world", List.of(), 0, null, 1);
        PlayerMock player = server.addPlayer();
        AtomicBoolean onSuccessRan = new AtomicBoolean(false);

        teleporter.proceedToTarget(player, gate, () -> onSuccessRan.set(true));

        assertFalse(onSuccessRan.get());
    }

    @Test
    void elitemobsDelegateWithoutPluginDoesNotRunOnSuccess() {
        // MockBukkit環境にはEliteMobsプラグインが存在しないため isAvailable() は常にfalseになる —
        // 「委譲経路がEliteMobs不在時にonSuccessを呼ばない」ことだけを検証する。
        DungeonGate gate = new DungeonGate("dungeon_abyss", List.of("dungeon_abyss"), 0, null, 1);
        PlayerMock player = server.addPlayer();
        AtomicBoolean onSuccessRan = new AtomicBoolean(false);

        teleporter.proceedToTarget(player, gate, () -> onSuccessRan.set(true));

        assertFalse(onSuccessRan.get());
    }
}
