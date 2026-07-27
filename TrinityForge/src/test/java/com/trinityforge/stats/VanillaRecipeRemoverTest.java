package com.trinityforge.stats;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaRecipeRemoverTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void bareNamesGetMinecraftNamespace() {
        Set<NamespacedKey> keys = VanillaRecipeRemover.normalizeKeys(
                List.of("iron_sword", "minecraft:oak_planks"), LOG);
        assertEquals(2, keys.size());
        assertTrue(keys.contains(NamespacedKey.minecraft("iron_sword")));
        assertTrue(keys.contains(NamespacedKey.minecraft("oak_planks")));
    }

    @Test
    void trinityforgeKeysAndMalformedKeysAreRefused() {
        Set<NamespacedKey> keys = VanillaRecipeRemover.normalizeKeys(
                List.of("trinityforge:catalog_source_gem", "bad key!", "", "  "), LOG);
        assertTrue(keys.isEmpty());
    }

    @Test
    void keysAreCaseInsensitiveAndDeduplicated() {
        Set<NamespacedKey> keys = VanillaRecipeRemover.normalizeKeys(
                List.of("IRON_SWORD", "iron_sword", " minecraft:iron_sword "), LOG);
        assertEquals(Set.of(NamespacedKey.minecraft("iron_sword")), keys);
    }

    @Test
    void datapackNamespacesAreAllowed() {
        Set<NamespacedKey> keys = VanillaRecipeRemover.normalizeKeys(
                List.of("somepack:custom_recipe"), LOG);
        assertEquals(1, keys.size());
        assertTrue(keys.contains(NamespacedKey.fromString("somepack:custom_recipe")));
    }

    @Test
    void nullListYieldsEmptySet() {
        assertTrue(VanillaRecipeRemover.normalizeKeys(null, LOG).isEmpty());
    }
}
