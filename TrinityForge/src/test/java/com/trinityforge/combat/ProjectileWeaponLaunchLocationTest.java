package com.trinityforge.combat;

import org.bukkit.Location;
import org.bukkit.entity.Arrow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-04 バグ修正(distance-damage-bonus 悪用防止): 発射地点を projectile PDC へ retain する
 * {@link ProjectileWeapon#storeLaunchLocation}/{@link ProjectileWeapon#readLaunchLocation} の往復。
 *
 * <p>この記録が無ければ {@code CombatListener} は距離ボーナスを常に0へフォールバックする契約なので
 * (射手の現在地への旧フォールバックは exploit を再開させるため禁止)、「記録なし」系の空系統も
 * ここで固定する。
 */
class ProjectileWeaponLaunchLocationTest {

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
    void unstampedProjectileHasNoRecordedLaunchLocation() {
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        assertTrue(ProjectileWeapon.readLaunchLocation(arrow).isEmpty(),
                "an arrow that never went through onProjectileLaunch must have no recorded launch point "
                        + "(dispenser-fired / plugin-spawned / pre-existing arrows)");
    }

    @Test
    void storedLaunchLocationRoundTrips() {
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        Location launch = new Location(world, 12.5, 70.0, -8.25);

        ProjectileWeapon.storeLaunchLocation(arrow, launch);

        Optional<Location> restored = ProjectileWeapon.readLaunchLocation(arrow);
        assertTrue(restored.isPresent());
        assertEquals(world, restored.get().getWorld());
        assertEquals(12.5, restored.get().getX(), 1e-9);
        assertEquals(70.0, restored.get().getY(), 1e-9);
        assertEquals(-8.25, restored.get().getZ(), 1e-9);
    }

    @Test
    void nullProjectileOrLocationIsSafeNoOp() {
        assertTrue(ProjectileWeapon.readLaunchLocation(null).isEmpty());
        ProjectileWeapon.storeLaunchLocation(null, new Location(world, 0, 0, 0)); // must not throw

        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        ProjectileWeapon.storeLaunchLocation(arrow, null); // must not throw
        assertTrue(ProjectileWeapon.readLaunchLocation(arrow).isEmpty());
    }

    @Test
    void locationWithNullWorldStoresNothing() {
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        ProjectileWeapon.storeLaunchLocation(arrow, new Location(null, 1, 2, 3));
        assertTrue(ProjectileWeapon.readLaunchLocation(arrow).isEmpty());
    }
}
