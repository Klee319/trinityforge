package com.trinityforge.stats;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Unregisters the vanilla/datapack recipes listed in {@code progression/crafting-features.yml
 * removed-vanilla-recipes} (editor: 便利機能 → バニラレシピ削除).
 *
 * <p>Removed {@link Recipe} objects are stashed so a {@code /trinityforge reload} that drops a key
 * from the list re-adds the recipe without a server restart. Keys are resolved lazily against the
 * live recipe registry, so datapack recipes work the same as {@code minecraft:} ones. TF's own
 * {@code trinityforge:} recipes are refused (they are managed by {@link CatalogRecipeRegistrar};
 * removing them from the editor means deleting the catalog recipe instead).
 *
 * <p>Fail-soft per key: an unknown key logs a warning and is skipped, matching how the rest of the
 * config layer treats bad entries.
 */
public final class VanillaRecipeRemover {

    private final Logger log;
    /** Recipes currently removed by this remover, kept for restore on reload. */
    private final Map<NamespacedKey, Recipe> stash = new LinkedHashMap<>();

    public VanillaRecipeRemover(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Applies {@code configuredKeys} (config order, {@code minecraft:} assumed when bare): removes
     * newly listed recipes and restores recipes that are no longer listed. Idempotent.
     */
    public void apply(List<String> configuredKeys) {
        Set<NamespacedKey> wanted = normalizeKeys(configuredKeys, log);
        boolean changed = false;

        // 1) restore recipes dropped from the list since the previous apply
        for (var it = stash.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<NamespacedKey, Recipe> entry = it.next();
            if (wanted.contains(entry.getKey())) {
                continue;
            }
            try {
                Bukkit.addRecipe(entry.getValue());
                changed = true;
            } catch (IllegalStateException ex) {
                // already re-registered by something else — treat as restored
                log.fine("removed-vanilla-recipes: '" + entry.getKey() + "' already restored: " + ex.getMessage());
            }
            it.remove();
        }

        // 2) remove newly listed recipes
        for (NamespacedKey key : wanted) {
            if (stash.containsKey(key)) {
                continue; // already removed by us
            }
            Recipe recipe = Bukkit.getRecipe(key);
            if (recipe == null) {
                log.warning("[progression/crafting-features.yml] removed-vanilla-recipes: レシピ '"
                        + key + "' は存在しません (スキップ)");
                continue;
            }
            if (Bukkit.removeRecipe(key)) {
                stash.put(key, recipe);
                changed = true;
            }
        }

        if (changed) {
            // resync client recipe books so removed recipes disappear without a relog
            try {
                Bukkit.updateRecipes();
            } catch (NoSuchMethodError | UnsupportedOperationException ignored) {
                // older API / test doubles: clients resync on next join
            }
            log.info("removed-vanilla-recipes: " + stash.size() + " 件のレシピを無効化中");
        }
    }

    /** Keys currently held removed by this remover (insertion order). */
    public Set<NamespacedKey> removedKeys() {
        return Set.copyOf(stash.keySet());
    }

    /**
     * Parses config strings into recipe keys. Bare names get the {@code minecraft:} namespace,
     * {@code trinityforge:} keys and malformed keys are refused with a warning. Pure — unit-testable
     * without a running server.
     */
    static Set<NamespacedKey> normalizeKeys(List<String> raw, Logger log) {
        Set<NamespacedKey> keys = new LinkedHashSet<>();
        if (raw == null) {
            return keys;
        }
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            NamespacedKey key = NamespacedKey.fromString(value.trim().toLowerCase(java.util.Locale.ROOT));
            if (key == null) {
                log.warning("removed-vanilla-recipes: 不正なレシピキー '" + value + "' (スキップ)");
                continue;
            }
            if ("trinityforge".equals(key.getNamespace())) {
                log.warning("removed-vanilla-recipes: trinityforge: レシピは対象外です ('" + value
                        + "'。カタログ側でレシピを削除してください)");
                continue;
            }
            keys.add(key);
        }
        return keys;
    }
}
