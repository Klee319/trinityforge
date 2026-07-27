package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code progression/use-requirements.yml}: global toggle for use-level / use-skill
 * enforcement on weapons and tools.
 */
public final class UseRequirementsConfig implements LoadableConfig {

    public static final String PATH = "progression/use-requirements.yml";

    private volatile boolean enforce = false;

    /** When false, use-level / use-skill requirements on items are recorded but never enforced. */
    public boolean enforce() {
        return enforce;
    }

    public String resourcePath() {
        return PATH;
    }

    @Override
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) {
            plugin.saveResource(PATH, false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML syntax error; keeping previous values: "
                    + ex.getMessage(), ex);
            return false;
        }
        enforce = yaml.getBoolean("enforce", false);
        log.info("[" + PATH + "] loaded enforce=" + enforce);
        return true;
    }
}
