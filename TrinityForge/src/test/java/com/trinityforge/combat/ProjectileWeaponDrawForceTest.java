package com.trinityforge.combat;

import org.bukkit.entity.Arrow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-07-25バグ修正 (#3): 弓の引き絞り量({@code EntityShootBowEvent#getForce()}) を projectile PDC へ
 * retain する {@link ProjectileWeapon#storeDrawForce}/{@link ProjectileWeapon#readDrawForce} の往復。
 */
class ProjectileWeaponDrawForceTest {

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
    void unstampedProjectileDefaultsToFullDraw() {
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        assertEquals(1.0, ProjectileWeapon.readDrawForce(arrow), 1e-9);
    }

    @Test
    void storedForceRoundTrips() {
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        ProjectileWeapon.storeDrawForce(arrow, 0.35f);
        assertEquals(0.35, ProjectileWeapon.readDrawForce(arrow), 1e-6);
    }

    @Test
    void storedForceIsClampedToZeroOneRange() {
        Arrow low = world.spawn(world.getSpawnLocation(), Arrow.class);
        ProjectileWeapon.storeDrawForce(low, -0.5f);
        assertEquals(0.0, ProjectileWeapon.readDrawForce(low), 1e-9);

        Arrow high = world.spawn(world.getSpawnLocation(), Arrow.class);
        ProjectileWeapon.storeDrawForce(high, 1.7f);
        assertEquals(1.0, ProjectileWeapon.readDrawForce(high), 1e-9);
    }

    @Test
    void nullProjectileIsSafeNoOp() {
        assertEquals(1.0, ProjectileWeapon.readDrawForce(null), 1e-9);
        ProjectileWeapon.storeDrawForce(null, 0.5f); // must not throw
    }
}
