package com.trinityforge.listeners;

import com.trinityforge.combat.DefenseStats;
import com.trinityforge.mobs.MobTransformCarryOver;
import com.trinityforge.pdc.MobData;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MobTransformListener}: 変身({@code EntityTransformEvent})でTFのモブステが失われないこと。
 *
 * <p>実サーバーでの発火順は「EntityTransformEvent → CreatureSpawnEvent」。本リスナーが前者で
 * 引き継ぐので、後者を見る {@code MobTypeSpawnListener} からダンジョンテーマ等が見える。
 */
class MobTransformListenerTest {

    private ServerMock server;
    private WorldMock world;
    private MobTransformListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        listener = new MobTransformListener();
        MobTransformCarryOver.clearForTests();
    }

    @AfterEach
    void tearDown() {
        MobTransformCarryOver.clearForTests();
        MockBukkit.unmock();
    }

    private static DefenseStats defense(double rate) {
        return new DefenseStats(rate, rate / 2.0, rate / 4.0, rate * 2.0, rate * 3.0);
    }

    private EntityTransformEvent transform(LivingEntity from, List<Entity> to,
                                           EntityTransformEvent.TransformReason reason) {
        return new EntityTransformEvent(from, to, reason);
    }

    @Test
    void zombieDrowningKeepsItsDungeonThemeAndCombatStats() {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        Drowned drowned = world.spawn(world.getSpawnLocation(), Drowned.class);
        MobData.stamp(zombie, 60, defense(0.4), defense(0.2));
        zombie.getPersistentDataContainer()
                .set(PdcKeys.MOB_DUNGEON_THEME, PersistentDataType.STRING, "sunken");

        listener.onTransform(transform(zombie, List.of(drowned),
                EntityTransformEvent.TransformReason.DROWNED));

        MobData after = MobData.of(drowned);
        assertEquals("sunken", after.dungeonTheme().orElse(null),
                "ダンジョン個体が変身後に「ただの野良モブ」へ化けている");
        assertEquals(60, after.level());
        assertEquals(0.4, after.defenseFor(com.trinityforge.combat.DamageType.PHYSICAL).defenseRate(), 1e-9);
    }

    @Test
    void spawnerOriginSurvivesDrowningSoTheExpNerfCannotBeBypassed() {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        Drowned drowned = world.spawn(world.getSpawnLocation(), Drowned.class);
        zombie.getPersistentDataContainer()
                .set(PdcKeys.MOB_SPAWNER_SPAWNED, PersistentDataType.BYTE, (byte) 1);

        listener.onTransform(transform(zombie, List.of(drowned),
                EntityTransformEvent.TransformReason.DROWNED));

        assertTrue(drowned.getPersistentDataContainer()
                        .has(PdcKeys.MOB_SPAWNER_SPAWNED, PersistentDataType.BYTE),
                "スポナー由来の印が消えると、水没させるだけでスポナーEXP抑制を回避できる");
    }

    @Test
    void damageAlreadyDealtIsNotErasedByTheTransformation() {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        Drowned drowned = world.spawn(world.getSpawnLocation(), Drowned.class);
        zombie.getAttribute(Attribute.MAX_HEALTH).setBaseValue(400.0);
        zombie.setHealth(100.0);

        listener.onTransform(transform(zombie, List.of(drowned),
                EntityTransformEvent.TransformReason.DROWNED));

        assertEquals(400.0, drowned.getAttribute(Attribute.MAX_HEALTH).getBaseValue(), 1e-9);
        assertEquals(100.0, drowned.getHealth(), 1e-9, "変身で全回復してはいけない");
        assertEquals(0.25, MobTransformCarryOver.consumeHealthRatio(drowned.getUniqueId()), 1e-9,
                "直後の CreatureSpawnEvent で刻み直す個体のためにHP割合が預けられていない");
    }

    @Test
    void cancelledTransformIsLeftCompletelyAlone() {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        Drowned drowned = world.spawn(world.getSpawnLocation(), Drowned.class);
        MobData.stamp(zombie, 60, defense(0.4), defense(0.2));
        EntityTransformEvent event = transform(zombie, List.of(drowned),
                EntityTransformEvent.TransformReason.DROWNED);
        // EliteMobs フォークは自分の管理下のモブについて本イベントをキャンセルする(＝変身は起きない)。
        event.setCancelled(true);

        listener.onTransform(event);

        assertFalse(MobData.of(drowned).hasProfile(),
                "キャンセルされた変身は起きないので、引き継ぎ処理も走ってはいけない");
        assertEquals(0, MobTransformCarryOver.pendingCountForTests());
    }

    @Test
    void slimeSplitCarriesOriginButNotHealth() {
        Slime parent = world.spawn(world.getSpawnLocation(), Slime.class);
        Slime childA = world.spawn(world.getSpawnLocation(), Slime.class);
        Slime childB = world.spawn(world.getSpawnLocation(), Slime.class);
        parent.getPersistentDataContainer()
                .set(PdcKeys.MOB_DUNGEON_THEME, PersistentDataType.STRING, "slimy");
        parent.getAttribute(Attribute.MAX_HEALTH).setBaseValue(600.0);
        parent.setHealth(600.0);
        double childBaseBefore = childA.getAttribute(Attribute.MAX_HEALTH).getBaseValue();

        listener.onTransform(transform(parent, List.of(childA, childB),
                EntityTransformEvent.TransformReason.SPLIT));

        assertEquals("slimy", MobData.of(childA).dungeonTheme().orElse(null),
                "分裂した子もダンジョン個体のままであること");
        assertEquals("slimy", MobData.of(childB).dungeonTheme().orElse(null));
        assertEquals(childBaseBefore, childA.getAttribute(Attribute.MAX_HEALTH).getBaseValue(), 1e-9,
                "分裂は1体→複数体なので親のHPを配ってはいけない(小スライムが親と同じ耐久になる)");
        assertEquals(0, MobTransformCarryOver.pendingCountForTests(),
                "分裂ではHP割合を預けない");
    }

    @Test
    void everyTransformedEntityGetsTheStamp() {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        Drowned a = world.spawn(world.getSpawnLocation(), Drowned.class);
        Drowned b = world.spawn(world.getSpawnLocation(), Drowned.class);
        MobData.stamp(zombie, 33, defense(0.1), defense(0.1));

        listener.onTransform(transform(zombie, List.of(a, b),
                EntityTransformEvent.TransformReason.DROWNED));

        assertEquals(33, MobData.of(a).level());
        assertEquals(33, MobData.of(b).level());
    }
}
