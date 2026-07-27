package com.trinityforge.stats;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * User-defined material equivalence lists ({@code items/material-lists.yml}), referenced from
 * recipe ingredients as {@code list:<id>} tokens. Replaces the removed hard-coded
 * {@code MaterialEquivalence} series: which materials count as interchangeable is now entirely
 * config-driven (editor: 素材欄の「互換リスト」ボタン).
 *
 * <p>Static snapshot holder so {@link RecipeIngredient} can resolve lazily without threading a
 * config reference through every parse site. {@link com.trinityforge.config.domains.MaterialListsConfig}
 * swaps the snapshot atomically on load/reload; resolution of an unknown list id returns an empty
 * set (the recipe registrar then skips that recipe with a warning instead of over-matching).
 */
public final class MaterialLists {

    /** Immutable lists+labels pair so a reload swaps both atomically (no mixed observation). */
    private record Snapshot(Map<String, Set<Material>> lists, Map<String, Set<String>> customIds,
                            Map<String, String> labels) {
    }

    private static volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of());

    private MaterialLists() {
    }

    /** Atomically replaces the current snapshot (called by MaterialListsConfig on load/reload). */
    public static void update(Map<String, Set<Material>> newLists, Map<String, Set<String>> newCustomIds,
                              Map<String, String> newLabels) {
        Map<String, Set<Material>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Material>> e : newLists.entrySet()) {
            frozen.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        Map<String, Set<String>> frozenCustom = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : newCustomIds.entrySet()) {
            frozenCustom.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        snapshot = new Snapshot(Collections.unmodifiableMap(frozen), Collections.unmodifiableMap(frozenCustom), Map.copyOf(newLabels));
    }

    /** Source-compatible helper for callers that only define vanilla material members. */
    public static void update(Map<String, Set<Material>> newLists, Map<String, String> newLabels) {
        update(newLists, Map.of(), newLabels);
    }

    /** Members of the list, or an empty set when the id is not defined. */
    public static Set<Material> resolve(String listId) {
        if (listId == null) {
            return Set.of();
        }
        Set<Material> members = snapshot.lists().get(listId);
        return members == null ? Set.of() : members;
    }

    public static boolean exists(String listId) {
        return listId != null && snapshot.lists().containsKey(listId);
    }

    /** custom:<id> members of a list. They may be TF catalog or externally registered items. */
    public static Set<String> resolveCustomIds(String listId) {
        if (listId == null) return Set.of();
        Set<String> members = snapshot.customIds().get(listId);
        return members == null ? Set.of() : members;
    }

    /** Display label for the list (editor-facing); falls back to the id. */
    public static String labelOf(String listId) {
        String label = snapshot.labels().get(listId);
        return label == null || label.isBlank() ? listId : label;
    }

    /** All list ids in config order (diagnostics/testing). */
    public static Set<String> ids() {
        return snapshot.lists().keySet();
    }
}
