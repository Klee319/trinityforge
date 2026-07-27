package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.mobs.DungeonTheme;
import com.trinityforge.mobs.RampParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code dungeon/themes.yml}: named attribute themes (defense-bias presets) used to
 * author a new dungeon (concern: support creating new EliteMobs dungeons). The {@code themes:}
 * section is open-ended (arbitrary theme names). A theme supplies the defender ramps the importer
 * applies when a dungeon's mobs are imported with {@code /trinityforge importmobs theme <theme> <dir>}.
 * Malformed entries are skipped with a warning; the rest load.
 *
 * <p>{@link #parse(ConfigurationSection, Logger)} is split from {@link #load(Plugin)} so it is
 * unit-testable headlessly without a Plugin.
 */
public final class DungeonThemeConfig implements LoadableConfig {

    public static final String PATH = "dungeon/themes.yml";
    private static final String ROOT = "themes";

    private volatile Map<String, DungeonTheme> themes = Map.of();

    public Optional<DungeonTheme> theme(String name) {
        return Optional.ofNullable(themes.get(name));
    }

    public Set<String> names() {
        return themes.keySet();
    }

    public Map<String, DungeonTheme> all() {
        return themes;
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

        // loadConfiguration(File) は構文エラーを握り潰して空configを返すため自前でload()する。
        // 構文エラー時は直前に成功ロード済みのthemes(初回失敗時はMap.of())を維持しfalseを返す。
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }
        ParseResult result = parse(yaml.getConfigurationSection(ROOT), log);
        this.themes = result.themes();

        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + result.themes().size() + " theme(s), "
                    + result.skipped() + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + result.themes().size() + " dungeon theme(s) OK");
        return true;
    }

    /** Pure parse of the {@code themes:} section. Invalid entries are skipped, not fatal. */
    static ParseResult parse(ConfigurationSection root, Logger log) {
        Map<String, DungeonTheme> parsed = new LinkedHashMap<>();
        int skipped = 0;
        if (root != null) {
            for (String name : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(name);
                if (entry == null) {
                    log.warning("[" + PATH + "] theme '" + name + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                try {
                    String themePath = PATH + ".themes." + name;
                    parsed.put(name, new DungeonTheme(
                            name,
                            RampParser.defenseRamp(entry, "physical", log, themePath),
                            RampParser.defenseRamp(entry, "magical", log, themePath),
                            RampParser.ramp(entry, "armor-strength", log, themePath)));
                } catch (RuntimeException ex) {
                    // Broad on purpose: keeps the load fail-soft if DungeonTheme/RampParser ever
                    // start validating (today nothing in the try throws).
                    log.warning("[" + PATH + "] theme '" + name + "' invalid (" + ex.getMessage() + "); skipped");
                    skipped++;
                }
            }
        }
        return new ParseResult(Map.copyOf(parsed), skipped);
    }

    /** Parse outcome: the immutable theme map and how many entries were skipped. */
    record ParseResult(Map<String, DungeonTheme> themes, int skipped) {
    }
}
