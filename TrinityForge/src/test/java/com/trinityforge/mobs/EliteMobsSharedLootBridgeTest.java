package com.trinityforge.mobs;

import org.bukkit.Material;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EliteMobsSharedLootBridge} の fail-soft 契約 (2026-08-09)。
 * <p>
 * 共有戦利品テーブルへ<b>実際に流れる</b>側 (エリートモブ・2人以上のダメージ寄与者・
 * インスタンス化ダンジョン) は EliteMobs 本体が要るのでここからは駆動できない。フォーク側の
 * {@code SharedLootTableTrinityForgeTest} が受け口の署名と後処理スキップを固定している。
 * ここで固定するのは<b>逆側</b>、つまり「EliteMobs が居ない / 引き取られなかったときに
 * ドロップが消えない」こと — 橋が壊れてもアイテムが失われないという、この設計の安全弁そのもの。
 */
class EliteMobsSharedLootBridgeTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("EliteMobs 不在なら引き取られず、ドロップは従来どおり getDrops() へ積まれる")
    void fallsBackToTheDropListWhenEliteMobsIsAbsent() {
        EntityDeathEvent event = deathEvent();
        ItemStack stack = new ItemStack(Material.DIAMOND, 3);

        assertFalse(EliteMobsSharedLootBridge.deliver(event, stack),
                "EliteMobs が居ないのに「引き取った」を返すとドロップが消える");
        assertEquals(1, event.getDrops().size());
        assertSame(stack, event.getDrops().get(0), "同じスタックがそのまま地面へ落ちること");
    }

    @Test
    @DisplayName("複数回 deliver しても取りこぼさない")
    void everyStackReachesTheDropList() {
        EntityDeathEvent event = deathEvent();
        EliteMobsSharedLootBridge.deliver(event, new ItemStack(Material.DIAMOND, 1));
        EliteMobsSharedLootBridge.deliver(event, new ItemStack(Material.EMERALD, 2));

        assertEquals(2, event.getDrops().size());
    }

    @Test
    @DisplayName("null / 空気は積まない (呼び出し側の null チェックを橋が肩代わりする)")
    void nullAndAirAreIgnored() {
        EntityDeathEvent event = deathEvent();

        assertFalse(EliteMobsSharedLootBridge.deliver(event, null));
        assertFalse(EliteMobsSharedLootBridge.deliver(event, new ItemStack(Material.AIR)));
        assertTrue(event.getDrops().isEmpty(), "空気を地面へ落とすと空のアイテムエンティティが湧く");
    }

    @Test
    @DisplayName("offer 単体は EliteMobs 不在で常に false (getDrops には触らない)")
    void offerIsPureWhenEliteMobsIsAbsent() {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        assertFalse(EliteMobsSharedLootBridge.offer(zombie, new ItemStack(Material.DIAMOND)));
        assertFalse(EliteMobsSharedLootBridge.offer(null, new ItemStack(Material.DIAMOND)));
        assertFalse(EliteMobsSharedLootBridge.offer(zombie, null));
    }

    private EntityDeathEvent deathEvent() {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        List<ItemStack> drops = new ArrayList<>();
        return new EntityDeathEvent(zombie, org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.GENERIC_KILL).build(), drops);
    }
}
