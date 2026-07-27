package com.trinityforge.stats;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeIngredientTest {

    @AfterEach
    void resetLists() {
        MaterialLists.update(Map.of(), Map.of());
    }

    @Test
    void parsesPlainMaterialAndRoundTrips() {
        RecipeIngredient ing = RecipeIngredient.parse("IRON_INGOT");
        assertEquals(Material.IRON_INGOT, ing.material());
        assertFalse(ing.isList());
        assertEquals("iron_ingot", ing.configValue());
    }

    @Test
    void parsesListTokenAndRoundTrips() {
        RecipeIngredient ing = RecipeIngredient.parse("list:planks");
        assertTrue(ing.isList());
        assertFalse(ing.isCustom());
        assertEquals("planks", ing.listId());
        assertEquals("list:planks", ing.configValue());
        // プレフィックスは大文字小文字混在も許容 (id はそのまま)
        assertEquals("wool", RecipeIngredient.parse("LIST:wool").listId());
    }

    @Test
    void listIngredientAcceptsAllMembersButExactAcceptsOnlyItself() {
        MaterialLists.update(
                Map.of("planks", Set.of(Material.OAK_PLANKS, Material.SPRUCE_PLANKS)),
                Map.of("planks", "板材"));
        RecipeIngredient list = RecipeIngredient.parse("list:planks");
        assertTrue(list.acceptsMaterial(Material.OAK_PLANKS));
        assertTrue(list.acceptsMaterial(Material.SPRUCE_PLANKS));
        assertFalse(list.acceptsMaterial(Material.OAK_LOG));
        assertEquals(Set.of(Material.OAK_PLANKS, Material.SPRUCE_PLANKS), list.acceptedMaterials());

        RecipeIngredient exact = RecipeIngredient.parse("OAK_PLANKS");
        assertTrue(exact.acceptsMaterial(Material.OAK_PLANKS));
        assertFalse(exact.acceptsMaterial(Material.SPRUCE_PLANKS));
        assertEquals(Set.of(Material.OAK_PLANKS), exact.acceptedMaterials());
    }

    @Test
    void unknownListResolvesToEmptySet() {
        RecipeIngredient ing = RecipeIngredient.parse("list:not_defined");
        assertTrue(ing.acceptedMaterials().isEmpty());
        assertFalse(ing.acceptsMaterial(Material.OAK_PLANKS));
    }

    @Test
    void legacyAnyTokenDowngradesToExactMaterial() {
        RecipeIngredient ing = RecipeIngredient.parse("any:OAK_LOG");
        assertEquals(Material.OAK_LOG, ing.material());
        assertFalse(ing.isList());
        assertEquals("oak_log", ing.configValue());
        assertTrue(ing.acceptsMaterial(Material.OAK_LOG));
        assertFalse(ing.acceptsMaterial(Material.STRIPPED_OAK_WOOD));
    }

    @Test
    void customTokenStillParses() {
        RecipeIngredient ing = RecipeIngredient.parse("custom:source_gem");
        assertTrue(ing.isCustom());
        assertEquals("custom:source_gem", ing.configValue());
        assertTrue(ing.acceptedMaterials().isEmpty());
    }

    @Test
    void unknownMaterialAndBlankTokensAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RecipeIngredient.parse("NOT_A_MATERIAL"));
        assertThrows(IllegalArgumentException.class, () -> RecipeIngredient.parse("any:NOT_A_MATERIAL"));
        assertThrows(IllegalArgumentException.class, () -> RecipeIngredient.parse("list:"));
        assertThrows(IllegalArgumentException.class, () -> RecipeIngredient.parse("custom:"));
    }
}
