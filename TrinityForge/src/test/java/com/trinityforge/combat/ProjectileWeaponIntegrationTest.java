package com.trinityforge.combat;

import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Trident;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
 * MockBukkit round-trip for {@link ProjectileWeapon}: the firing weapon stored on a projectile at launch
 * must survive serialize -> PDC write -> read -> deserialize, so impact-time stat derivation uses the
 * bow/crossbow/trident that actually fired (High bug). Crucially covers the Fix A change: a non-AIR
 * weapon with NO item meta is still retained (the old {@code hasItemMeta()} gate dropped valid bare
 * weapons and let impact wrongly fall back to the shooter's mainhand).
 */
class ProjectileWeaponIntegrationTest {

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
    void bareBowWithoutMetaIsRetainedAndRoundTrips() {
        ItemStack bow = new ItemStack(Material.BOW);
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);

        ProjectileWeapon.store(arrow, bow);

        Optional<ItemStack> restored = ProjectileWeapon.read(arrow);
        assertTrue(restored.isPresent(), "a non-AIR bow must be retained even with no item meta");
        assertEquals(Material.BOW, restored.get().getType());
    }

    @Test
    void bowWithMetaRoundTripsPreservingName() {
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta meta = bow.getItemMeta();
        meta.setCustomModelData(100012);
        bow.setItemMeta(meta);
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);

        ProjectileWeapon.store(arrow, bow);

        ItemStack restored = ProjectileWeapon.read(arrow).orElseThrow();
        assertEquals(Material.BOW, restored.getType());
        assertTrue(restored.hasItemMeta() && restored.getItemMeta().hasCustomModelData(),
                "stored meta (CustomModelData) must survive the round-trip");
    }

    @Test
    void tridentItemRoundTrips() {
        ItemStack tridentItem = new ItemStack(Material.TRIDENT);
        Trident trident = world.spawn(world.getSpawnLocation(), Trident.class);

        ProjectileWeapon.store(trident, tridentItem);

        assertEquals(Material.TRIDENT, ProjectileWeapon.read(trident).orElseThrow().getType());
    }

    @Test
    void airWeaponStoresNothingAndReadIsEmpty() {
        ItemStack air = new ItemStack(Material.AIR);
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);

        ProjectileWeapon.store(arrow, air);

        assertTrue(ProjectileWeapon.read(arrow).isEmpty(),
                "an AIR weapon must store nothing so impact falls back to the mainhand");
    }
}
