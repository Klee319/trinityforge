package com.trinityforge.combat;

import org.bukkit.entity.Arrow;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-13バグ修正(オフハンド発射のステ門迂回防止): 「どちらの手から撃ったか」を projectile PDC へ
 * retain する {@link ProjectileWeapon#storeFiredFromOffhand}/{@link ProjectileWeapon#readFiredFromOffhand}
 * の往復と、{@code EntityShootBowEvent#getHand()} → boolean の純粋変換 {@link ProjectileWeapon#isOffhand}。
 */
class ProjectileWeaponFiredFromOffhandTest {

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
    void unstampedProjectileDefaultsToMainhand() {
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        assertFalse(ProjectileWeapon.readFiredFromOffhand(arrow),
                "no record (trident / pre-stamp arrow / non-TF projectile) must default to mainhand (false)");
    }

    @Test
    void storedOffhandFlagRoundTrips() {
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        ProjectileWeapon.storeFiredFromOffhand(arrow, true);
        assertTrue(ProjectileWeapon.readFiredFromOffhand(arrow));
    }

    @Test
    void storedMainhandFlagRoundTrips() {
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        ProjectileWeapon.storeFiredFromOffhand(arrow, false);
        assertFalse(ProjectileWeapon.readFiredFromOffhand(arrow));
    }

    @Test
    void nullProjectileIsSafeNoOp() {
        assertFalse(ProjectileWeapon.readFiredFromOffhand(null));
        ProjectileWeapon.storeFiredFromOffhand(null, true); // must not throw
    }

    @Test
    void isOffhand_mapsOffHandToTrue() {
        assertTrue(ProjectileWeapon.isOffhand(EquipmentSlot.OFF_HAND));
    }

    @Test
    void isOffhand_mapsMainHandToFalse() {
        assertFalse(ProjectileWeapon.isOffhand(EquipmentSlot.HAND));
    }

    @Test
    void isOffhand_mapsNullToFalse() {
        // EntityShootBowEvent#getHand() の実装差(null を返し得る)への防御。null は「メインハンド扱い」
        // (=既定の store 値と同じ false)に倒す。
        assertFalse(ProjectileWeapon.isOffhand(null));
    }
}
