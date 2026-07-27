package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Recipe;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PRG-02: {@code CatalogCraftGateListener} must gate BOTH TF catalog recipes and vanilla recipes by the
 * same {@code recipe:<id>} dynamic-gate mechanism, with the safety property the coordinator called out
 * as non-negotiable: a recipe id that no skill-tree node references must always stay craftable (default
 * open). Only ids actually present in {@link DedicatedEffectsConfig#recipeGatePerks()} may ever block.
 */
class CatalogCraftGateListenerTest {

    private interface KeyedRecipe extends Recipe, Keyed {
    }

    private static KeyedRecipe keyedRecipe(NamespacedKey key) {
        KeyedRecipe recipe = mock(KeyedRecipe.class);
        when(recipe.getKey()).thenReturn(key);
        return recipe;
    }

    // ---- resolveGateId: recipe key -> gate id -------------------------------------------------

    @Test
    void resolveGateId_tfCatalogRecipe_stripsCatalogPrefix() {
        Recipe recipe = keyedRecipe(new NamespacedKey("trinityforge", "catalog_tf_core_wood"));
        assertEquals("tf_core_wood", CatalogCraftGateListener.resolveGateId(recipe));
    }

    @Test
    void resolveGateId_tfNamespaceWithoutCatalogPrefix_isNull() {
        Recipe recipe = keyedRecipe(new NamespacedKey("trinityforge", "something_else"));
        assertNull(CatalogCraftGateListener.resolveGateId(recipe));
    }

    @Test
    void resolveGateId_vanillaRecipe_usesKeyPathAsIs() {
        Recipe recipe = keyedRecipe(NamespacedKey.minecraft("diamond_sword"));
        assertEquals("diamond_sword", CatalogCraftGateListener.resolveGateId(recipe));
    }

    @Test
    void resolveGateId_unkeyedRecipe_isNull() {
        Recipe recipe = mock(Recipe.class); // not a Keyed
        assertNull(CatalogCraftGateListener.resolveGateId(recipe));
    }

    // ---- isBlocked: the three required behaviours ---------------------------------------------

    @Test
    void unplacedVanillaRecipe_isNeverBlocked() {
        // 最重要: どのスキルツリーノードにも配置されていないIDは常にcraftableでなければならない。
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.recipeGatePerks()).thenReturn(Map.of()); // 何も配置されていない
        CatalogCraftGateListener listener = new CatalogCraftGateListener(dedicatedEffects);
        Player player = mock(Player.class);

        assertFalse(listener.isBlocked("iron_sword", player));
        assertFalse(listener.isBlocked("wooden_axe", player));
    }

    @Test
    void placedRecipe_lockedWhenPerkNotHeld() {
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.recipeGatePerks())
                .thenReturn(Map.of("diamond_sword", Set.of("smithing_perk_c")));
        when(dedicatedEffects.isActive(org.mockito.ArgumentMatchers.any(Player.class),
                org.mockito.ArgumentMatchers.eq("recipe:diamond_sword"))).thenReturn(false);
        CatalogCraftGateListener listener = new CatalogCraftGateListener(dedicatedEffects);
        Player player = mock(Player.class);

        assertTrue(listener.isBlocked("diamond_sword", player));
    }

    @Test
    void placedRecipe_openWhenPerkHeld() {
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.recipeGatePerks())
                .thenReturn(Map.of("diamond_sword", Set.of("smithing_perk_c")));
        when(dedicatedEffects.isActive(org.mockito.ArgumentMatchers.any(Player.class),
                org.mockito.ArgumentMatchers.eq("recipe:diamond_sword"))).thenReturn(true);
        CatalogCraftGateListener listener = new CatalogCraftGateListener(dedicatedEffects);
        Player player = mock(Player.class);

        assertFalse(listener.isBlocked("diamond_sword", player));
    }

    @Test
    void nullGateId_isNeverBlocked() {
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.recipeGatePerks()).thenReturn(Map.of());
        CatalogCraftGateListener listener = new CatalogCraftGateListener(dedicatedEffects);
        assertFalse(listener.isBlocked(null, mock(Player.class)));
    }
}
