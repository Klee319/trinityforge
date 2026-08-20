package com.trinityforge.stats;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only identity registry for custom items owned by another plugin.
 *
 * <p>Entries deliberately only describe the public, stable identity we can verify without
 * depending on the other plugin: Bukkit material plus CustomModelData.  They are never assembled
 * or given by TrinityForge; they merely make a {@code custom:<id>} recipe ingredient and a
 * material-equivalence list able to recognise an externally supplied stack.
 *
 * <p><strong>Layering (reload safety):</strong> the merged view seen by {@link #find} is built from
 * two kinds of source, each of which can reload independently without clobbering the other:
 * <ul>
 *   <li>the <em>local</em> layer — {@code items/external-items.yml}, replaced wholesale by
 *       {@link #update(Map)} on every TrinityForge config load ({@code ExternalItemsConfig}).</li>
 *   <li>zero or more <em>plugin</em> layers — one per contributing plugin (e.g. ArsPaper), each
 *       replaced wholesale by {@link #updateExternalPlugin(String, Map)} keyed by a stable
 *       {@code source} name so that plugin's own reload only touches its own layer.</li>
 * </ul>
 * On merge, plugin layers are applied first and the local layer is applied last so an explicit
 * {@code external-items.yml} entry always wins an id collision. A plugin layer registered with an
 * empty map (e.g. that plugin disabling) simply drops out of the merge instead of leaving stale
 * entries behind. TrinityForge reloading {@code external-items.yml} never touches plugin layers,
 * and a plugin reloading its own layer never touches TF's local layer or another plugin's layer.
 */
public final class ExternalItemRegistry {

    public record Definition(String id, Material material, int customModelData, String displayName) {
        public boolean matches(ItemStack stack, Integer cmd) {
            return stack != null && stack.getType() == material && cmd != null && cmd == customModelData;
        }
    }

    private static volatile Map<String, Definition> localDefinitions = Map.of();
    private static final Map<String, Map<String, Definition>> pluginLayers = new ConcurrentHashMap<>();
    private static volatile Map<String, Definition> definitions = Map.of();

    private ExternalItemRegistry() {
    }

    /** TrinityForge's own {@code items/external-items.yml} layer. See class javadoc for layering. */
    public static void update(Map<String, Definition> values) {
        localDefinitions = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        rebuild();
    }

    /**
     * A single contributing plugin's layer, identified by a stable {@code source} name (e.g.
     * {@code "arspaper"}). Replaces only that plugin's prior contribution — TF's local layer and
     * any other plugin's layer are untouched. Passing an empty/{@code null} map removes the
     * source's layer entirely (used on that plugin's disable/empty reload) rather than leaving
     * stale entries merged in.
     */
    public static void updateExternalPlugin(String source, Map<String, Definition> values) {
        Objects.requireNonNull(source, "source");
        if (values == null || values.isEmpty()) {
            pluginLayers.remove(source);
        } else {
            pluginLayers.put(source, Collections.unmodifiableMap(new LinkedHashMap<>(values)));
        }
        rebuild();
    }

    private static void rebuild() {
        Map<String, Definition> merged = new LinkedHashMap<>();
        for (Map<String, Definition> layer : pluginLayers.values()) {
            merged.putAll(layer);
        }
        merged.putAll(localDefinitions); // explicit external-items.yml entries always win a collision
        definitions = Collections.unmodifiableMap(merged);
    }

    public static Optional<Definition> find(String id) {
        return Optional.ofNullable(id == null ? null : definitions.get(id));
    }

    /** Finds an external identity by the stable cross-plugin signature available to TF. */
    public static Optional<Definition> find(Material material, Integer customModelData) {
        if (material == null || customModelData == null) return Optional.empty();
        return definitions.values().stream()
                .filter(definition -> definition.material() == material
                        && definition.customModelData() == customModelData)
                .findFirst();
    }

    /**
     * The {@code source} name of the plugin layer that claims this material+CustomModelData, if any.
     *
     * <p>Unlike {@link #find(Material, Integer)} this deliberately ignores the <em>local</em> layer
     * ({@code items/external-items.yml}): the question it answers is "which other plugin owns this
     * stack", and TF describing a foreign item in its own yml does not make TF the owner. Returns
     * empty for TF catalog items and for plain vanilla stacks.
     *
     * <p>Used by {@code CatalogWorkbenchListener} to decide whether the plugin that registered the
     * currently selected recipe is also the owner of every custom stack on the grid — in that case
     * TF must not clear the result, because that plugin's own per-slot guard is authoritative.
     */
    public static Optional<String> pluginSourceOf(Material material, Integer customModelData) {
        if (material == null || customModelData == null) return Optional.empty();
        for (Map.Entry<String, Map<String, Definition>> layer : pluginLayers.entrySet()) {
            for (Definition definition : layer.getValue().values()) {
                if (definition.material() == material
                        && definition.customModelData() == customModelData) {
                    return Optional.of(layer.getKey());
                }
            }
        }
        return Optional.empty();
    }

    public static boolean matches(String id, ItemStack stack, Integer cmd) {
        return find(id).map(definition -> definition.matches(stack, cmd)).orElse(false);
    }
}
