package com.trinityforge.mob;

import com.trinityforge.config.domains.DisplayConfig;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Bug fix regression: an {@link ArmorStand} implements {@link org.bukkit.entity.LivingEntity} in the
 * Bukkit API (it can be "damaged" and has an HP-like attribute), so a naive {@code instanceof
 * LivingEntity} damage-popup check spawns the cosmetic damage-number display above armor stands too.
 * {@link DamagePopupDisplay} must exclude {@link ArmorStand} while still showing the popup for a normal
 * mob (regression guard for the ArmorStand check swallowing real victims too).
 */
class DamagePopupDisplayTest {

    private ServerMock server;
    private WorldMock world;
    private DamagePopupDisplay display;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        Plugin plugin = MockBukkit.createMockPlugin();
        display = new DamagePopupDisplay(plugin, new DisplayConfig());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private EntityDamageByEntityEvent hit(Player attacker, org.bukkit.entity.Entity victim, double damage) {
        return new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, damage);
    }

    @Test
    void armorStandVictimSpawnsNoPopup() {
        Player attacker = server.addPlayer();
        ArmorStand stand = world.spawn(world.getSpawnLocation(), ArmorStand.class);

        display.onEntityDamageByEntity(hit(attacker, stand, 5.0));

        assertEquals(0, world.getEntitiesByClass(TextDisplay.class).size(),
                "an armor stand must never get the damage popup");
    }

    @Test
    void invisibleMarkerArmorStandVictimSpawnsNoPopup() {
        Player attacker = server.addPlayer();
        ArmorStand stand = world.spawn(world.getSpawnLocation(), ArmorStand.class);
        stand.setMarker(true);
        stand.setInvisible(true);

        display.onEntityDamageByEntity(hit(attacker, stand, 5.0));

        assertEquals(0, world.getEntitiesByClass(TextDisplay.class).size(),
                "an invisible marker armor stand must never get the damage popup either");
    }

    /**
     * Regression guard: the ArmorStand exclusion must not swallow a real victim too. MockBukkit's
     * {@code TextDisplay} mock does not implement {@code setBillboard} ({@code
     * UnimplementedOperationException}), so the popup's full visual spawn cannot be asserted directly
     * here — but that exception is itself the proof: it can only be thrown from INSIDE {@link
     * DamagePopupDisplay#spawnPopup}, meaning none of the guard clauses (armor stand / training dummy /
     * min-damage) returned early for this normal mob. A silent no-op (no exception at all) would mean the
     * fix over-broadly excluded real victims.
     */
    @Test
    void normalMobVictimStillSpawnsPopup() {
        Player attacker = server.addPlayer();
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);

        assertThrows(UnimplementedOperationException.class,
                () -> display.onEntityDamageByEntity(hit(attacker, zombie, 5.0)),
                "a normal mob must still reach the popup spawn path (MockBukkit's display mock is the "
                        + "limiting factor here, not the listener — see javadoc)");
    }
}
