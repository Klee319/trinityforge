package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.stats.ExternalItemRegistry;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Loads external custom-item identities used only as recipe/list references. */
public final class ExternalItemsConfig implements LoadableConfig {
    public static final String PATH = "items/external-items.yml";

    @Override
    public boolean load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) plugin.saveResource(PATH, false);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "[" + PATH + "] YAML構文エラー。直前の外部アイテム定義を維持します", ex);
            return false;
        }
        return parseInto(yaml.getConfigurationSection("items"), plugin.getLogger());
    }

    static boolean parseInto(ConfigurationSection root, Logger log) {
        Map<String, ExternalItemRegistry.Definition> parsed = new LinkedHashMap<>();
        boolean valid = true;
        if (root != null) for (String id : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(id);
            if (entry == null || !id.matches("[a-z0-9_]+")) {
                log.warning("[" + PATH + "] invalid external item id '" + id + "' ignored");
                valid = false;
                continue;
            }
            Material material = Material.matchMaterial(entry.getString("material", ""));
            int cmd = entry.getInt("custom-model-data", -1);
            if (material == null || cmd < 0) {
                log.warning("[" + PATH + "] item '" + id + "' requires material and non-negative custom-model-data");
                valid = false;
                continue;
            }
            parsed.put(id, new ExternalItemRegistry.Definition(id, material, cmd, entry.getString("display-name", id)));
        }
        ExternalItemRegistry.update(parsed);
        log.info("[" + PATH + "] loaded " + parsed.size() + " external item(s)" + (valid ? " OK" : " with warnings"));
        return valid;
    }
}
