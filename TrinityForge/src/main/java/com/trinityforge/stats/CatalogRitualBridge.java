package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.logging.Level;

/**
 * Registers catalog.yml {@code recipe.method: ritual} entries into ArsPaper's ritual registry when
 * ArsPaper is present. Fail-soft: missing Ars / missing API / reflection errors are logged and skipped
 * so TrinityForge still boots; workbench recipes remain handled by {@link CatalogRecipeRegistrar}.
 */
public final class CatalogRitualBridge {

    private CatalogRitualBridge() {
    }

    /**
     * Best-effort push of ritual recipes from the live catalog into ArsPaper.
     * Expected Ars hook: {@code CatalogRitualRegistrar.registerTrinityForgeCatalog(Plugin, Map)}.
     */
    public static void registerAll(Plugin plugin, ItemCatalogConfig catalog) {
        Plugin ars = Bukkit.getPluginManager().getPlugin("ArsPaper");
        if (ars == null || !ars.isEnabled()) {
            // Ars loads after TF; ArsPaper.onEnable calls TrinityForgeBridge.repushCatalogRituals().
            return;
        }
        try {
            // Ars join-classpath is one-way (Ars→TF). Load registrar via Ars's classloader.
            Class<?> registrar = Class.forName(
                    "com.arspaper.ritual.CatalogRitualRegistrar", true, ars.getClass().getClassLoader());
            Method register = registrar.getMethod("registerTrinityForgeCatalog", Plugin.class, Map.class);
            register.invoke(null, plugin, catalog.all());
            plugin.getLogger().info("[catalog-ritual] pushed ritual recipes to ArsPaper");
        } catch (ClassNotFoundException missing) {
            plugin.getLogger().info("[catalog-ritual] ArsPaper CatalogRitualRegistrar not present yet; "
                    + "ritual recipes in catalog.yml are stored but not registered at runtime");
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().log(Level.WARNING, "[catalog-ritual] failed to register ritual recipes", ex);
        }
    }
}
