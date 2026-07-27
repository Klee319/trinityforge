package com.trinityforge.mob;

import com.trinityforge.config.domains.MobOverridesConfig;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Bug fix regression: {@link ArmorStand} implements {@link LivingEntity} in the Bukkit API, so a naive
 * {@code instanceof LivingEntity} focus-target check would float the level/name/HP label above armor
 * stands too. {@link FocusHpDisplay#findFocusTarget} (invoked via reflection — it is intentionally
 * private, the tick loop is the only production caller) must skip an {@link ArmorStand} the player is
 * looking straight at, while still picking a normal mob in the same spot (regression guard for the
 * exclusion swallowing real targets too).
 *
 * <p>{@code findFocusTarget} first tries {@link LivingEntity#getTargetEntity}, which MockBukkit does not
 * implement ({@code UnimplementedOperationException}, which the JUnit engine reports as an aborted/skipped
 * test rather than a failure — easy to miss). The player under test is wrapped in a {@link
 * org.mockito.Mockito#spy} that stubs {@code getTargetEntity} to {@code null} so the method falls through
 * to its look-cone fallback scan (the code path that actually contains the fix), exactly as it would on a
 * real server when the direct raycast misses.
 */
class FocusHpDisplayTest {

    private ServerMock server;
    private WorldMock world;
    private FocusHpDisplay display;
    private Method findFocusTarget;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        Plugin plugin = MockBukkit.createMockPlugin();
        // 2026-07-26: 表示名は MobDisplayNames が解決する。ここでは overrides を持たない空の
        // MobOverridesConfig を渡す = 従来どおり customName → EntityType の順にフォールバックする。
        display = new FocusHpDisplay(plugin, new MobDisplayNames(new MobOverridesConfig()));
        findFocusTarget = FocusHpDisplay.class.getDeclaredMethod("findFocusTarget", Player.class);
        findFocusTarget.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private LivingEntity invoke(Player player) throws Exception {
        try {
            return (LivingEntity) findFocusTarget.invoke(display, player);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    /**
     * Player at origin looking south (+Z, yaw 0 / pitch 0); target 3 blocks straight ahead. The player is
     * a spy so {@code getTargetEntity} (unimplemented by MockBukkit) never actually runs — see class doc.
     */
    private Player lookingSouthAt(org.bukkit.entity.Entity target) {
        Player realPlayer = server.addPlayer();
        realPlayer.teleport(new Location(world, 0, 64, 0, 0f, 0f));
        target.teleport(new Location(world, 0, 64, 3, 0f, 0f));
        Player player = spy(realPlayer);
        doReturn(null).when(player).getTargetEntity(anyInt());
        return player;
    }

    @Test
    void armorStandInLookConeIsNotPicked() throws Exception {
        ArmorStand stand = world.spawn(world.getSpawnLocation(), ArmorStand.class);
        Player player = lookingSouthAt(stand);

        assertNull(invoke(player), "an armor stand directly in the look cone must never be focus-targeted");
    }

    @Test
    void normalMobInLookConeIsStillPicked() throws Exception {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        Player player = lookingSouthAt(zombie);

        LivingEntity target = invoke(player);
        assertNotNull(target,
                "a normal mob in the same spot must still be focus-targeted (regression guard: the "
                        + "ArmorStand exclusion must not swallow real targets)");
    }
}
