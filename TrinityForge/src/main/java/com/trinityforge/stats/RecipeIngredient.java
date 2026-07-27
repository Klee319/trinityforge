package com.trinityforge.stats;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One workbench-recipe ingredient from {@code items/catalog.yml}: a plain Bukkit
 * {@link Material}, a {@code list:<id>} token (any member of the user-defined
 * {@link MaterialLists} equivalence list — 素材欄の「互換リスト」), or a
 * {@code custom:<catalogId>} reference to another catalog entry (or an ArsPaper custom item
 * sharing that id).
 *
 * <p>Exactly one of {@link #material()} / {@link #catalogId()} / {@link #listId()} is non-null.
 * A {@code custom:} ingredient is matched at craft time by catalog identity (PDC
 * {@code catalogId}, falling back to material + CustomModelData), NOT by exact item meta, so items
 * that later gained extra PDC (quality stamp, bind owner) still match.
 *
 * <p>The legacy {@code any:<MATERIAL>} token (removed hard-coded equivalence series) is still
 * parsed for backward compatibility but downgrades to an exact-material ingredient.
 */
public record RecipeIngredient(Material material, String catalogId, String listId) {

    private static final String CUSTOM_PREFIX = "custom:";
    private static final String LIST_PREFIX = "list:";
    private static final String LEGACY_ANY_PREFIX = "any:";

    public RecipeIngredient {
        int set = (material != null ? 1 : 0) + (catalogId != null ? 1 : 0) + (listId != null ? 1 : 0);
        if (set != 1) {
            throw new IllegalArgumentException("exactly one of material/catalogId/listId must be set");
        }
        if (catalogId != null && catalogId.isBlank()) {
            throw new IllegalArgumentException("custom ingredient id must not be blank");
        }
        if (listId != null && listId.isBlank()) {
            throw new IllegalArgumentException("list ingredient id must not be blank");
        }
    }

    public static RecipeIngredient ofMaterial(Material material) {
        return new RecipeIngredient(Objects.requireNonNull(material, "material"), null, null);
    }

    /** {@code list:<id>}: any member of the user-defined material list. */
    public static RecipeIngredient ofList(String listId) {
        return new RecipeIngredient(null, null, Objects.requireNonNull(listId, "listId"));
    }

    public static RecipeIngredient ofCatalog(String catalogId) {
        return new RecipeIngredient(null, Objects.requireNonNull(catalogId, "catalogId"), null);
    }

    public boolean isCustom() {
        return catalogId != null;
    }

    public boolean isList() {
        return listId != null;
    }

    /**
     * The set of concrete materials this ingredient accepts: the whole user-defined list for
     * {@code list:} ingredients (empty when the list id is not defined — the registrar then skips
     * the recipe with a warning), the single material otherwise. Empty for {@code custom:}
     * ingredients.
     */
    public Set<Material> acceptedMaterials() {
        if (listId != null) {
            return MaterialLists.resolve(listId);
        }
        if (material == null) {
            return Set.of();
        }
        return Set.of(material);
    }

    /** Material-level match for one grid slot (custom identity is checked separately). */
    public boolean acceptsMaterial(Material candidate) {
        if (candidate == null) {
            return false;
        }
        if (listId != null) {
            return MaterialLists.resolve(listId).contains(candidate);
        }
        return material != null && candidate == material;
    }

    /**
     * Parses a config token: {@code "custom:<id>"} → catalog reference, {@code "list:<id>"} →
     * user-defined equivalence list, otherwise a Bukkit material name (legacy {@code "any:<mat>"}
     * downgrades to the exact material). Throws {@link IllegalArgumentException} on an unknown
     * material so the catalog loader can reject just the recipe (fail-soft convention).
     */
    public static RecipeIngredient parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("ingredient must not be blank");
        }
        String token = raw.trim();
        if (token.regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length())) {
            String id = token.substring(CUSTOM_PREFIX.length()).trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("custom ingredient id must not be blank");
            }
            return ofCatalog(id);
        }
        if (token.regionMatches(true, 0, LIST_PREFIX, 0, LIST_PREFIX.length())) {
            String id = token.substring(LIST_PREFIX.length()).trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("list ingredient id must not be blank");
            }
            return ofList(id);
        }
        // legacy any:<material> — the hard-coded equivalence series was removed; exact match now.
        if (token.regionMatches(true, 0, LEGACY_ANY_PREFIX, 0, LEGACY_ANY_PREFIX.length())) {
            token = token.substring(LEGACY_ANY_PREFIX.length()).trim();
        }
        Material material = Material.matchMaterial(token);
        if (material == null) {
            throw new IllegalArgumentException("unknown material '" + raw + "'");
        }
        return ofMaterial(material);
    }

    /** Round-trip config form: {@code "custom:<id>"} / {@code "list:<id>"} / material name. */
    public String configValue() {
        if (isCustom()) {
            return CUSTOM_PREFIX + catalogId;
        }
        if (isList()) {
            return LIST_PREFIX + listId;
        }
        return material.name().toLowerCase(Locale.ROOT);
    }
}
