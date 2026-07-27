package com.trinityforge.listeners;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure matching rules for particle-seed合成 (2026-07-23-stat-gate-overhaul §6.1). */
class ParticleSeedMatcherTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void matchesBareMaterialSpec() {
        assertTrue(ParticleSeedMatcher.matchesSeed(item(Material.DIAMOND), "DIAMOND"));
        assertTrue(ParticleSeedMatcher.matchesSeed(item(Material.DIAMOND), "diamond"), "大文字小文字を区別しない");
        assertFalse(ParticleSeedMatcher.matchesSeed(item(Material.EMERALD), "DIAMOND"));
    }

    @Test
    void rejectsNullOrAirOrBlankSpec() {
        assertFalse(ParticleSeedMatcher.matchesSeed(null, "DIAMOND"));
        assertFalse(ParticleSeedMatcher.matchesSeed(item(Material.AIR), "DIAMOND"));
        assertFalse(ParticleSeedMatcher.matchesSeed(item(Material.DIAMOND), ""));
        assertFalse(ParticleSeedMatcher.matchesSeed(item(Material.DIAMOND), null));
    }

    @Test
    void isToolOrWeaponCoversExpectedFamilies() {
        assertTrue(ParticleSeedMatcher.isToolOrWeapon(Material.DIAMOND_SWORD));
        assertTrue(ParticleSeedMatcher.isToolOrWeapon(Material.IRON_AXE));
        assertTrue(ParticleSeedMatcher.isToolOrWeapon(Material.NETHERITE_PICKAXE));
        assertTrue(ParticleSeedMatcher.isToolOrWeapon(Material.WOODEN_SHOVEL));
        assertTrue(ParticleSeedMatcher.isToolOrWeapon(Material.STONE_HOE));
        assertTrue(ParticleSeedMatcher.isToolOrWeapon(Material.BOW));
        assertTrue(ParticleSeedMatcher.isToolOrWeapon(Material.CROSSBOW));
        assertTrue(ParticleSeedMatcher.isToolOrWeapon(Material.TRIDENT));
        assertTrue(ParticleSeedMatcher.isToolOrWeapon(Material.MACE));
        assertFalse(ParticleSeedMatcher.isToolOrWeapon(Material.DIAMOND));
        assertFalse(ParticleSeedMatcher.isToolOrWeapon(null));
    }

    private static org.bukkit.inventory.ItemStack item(Material material) {
        return new org.bukkit.inventory.ItemStack(material);
    }
}
