package com.trinityforge.config.domains;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bundled novice spell-book recipe contract.
 *
 * <p>The recipe intentionally uses plain {@code IRON_*} ingredients. Craft-time identity checks
 * treat every stack without CustomModelData as vanilla, even if quality or stale catalog PDC is
 * present; CMD-bearing catalog equipment remains distinct.
 */
class SpellBookRecipeConfigTest {

    @Test
    void noviceBookUsesPlainIronEquipmentIngredients() {
        YamlConfiguration catalog = load("/items/catalog.yml");
        List<String> ingredients =
                catalog.getStringList("items.spell_book_novice.recipe.ingredients");

        assertEquals(5, ingredients.size());
        assertEquals(Set.of("BOOK", "IRON_SWORD", "IRON_SHOVEL", "IRON_AXE", "IRON_PICKAXE"),
                Set.copyOf(ingredients));
    }

    private static YamlConfiguration load(String resource) {
        try (var reader = new InputStreamReader(
                Objects.requireNonNull(SpellBookRecipeConfigTest.class.getResourceAsStream(resource), resource),
                StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (java.io.IOException ex) {
            throw new AssertionError("failed to close bundled resource " + resource, ex);
        }
    }
}
