package com.trinityforge.config.domains;

import com.trinityforge.stats.QualityTier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/quality-tiers.yml}: the ORDERED list of named quality tiers (ITEM_ECONOMY_SPEC
 * 5, ValhallaMMO quality-name 踏襲). One entry per quality level, so the list length also defines the
 * number of quality steps (Q = 任意段階 config-variable): {@link #effectiveMaxQuality} is
 * {@code size - 1} (quality is 0-indexed), or -1 when the list is empty (fall back to the numeric
 * {@code stats/quality.yml max-quality}). Tier {@code i} names quality level {@code i}.
 */
public final class QualityTiersConfig {

    public static final String PATH = "stats/quality-tiers.yml";
    private static final String ROOT = "tiers";

    private volatile List<QualityTier> tiers = List.of();

    public List<QualityTier> tiers() {
        return tiers;
    }

    /** The highest quality level these tiers cover (size-1), or -1 when no tiers are configured. */
    public int effectiveMaxQuality() {
        return tiers.isEmpty() ? -1 : tiers.size() - 1;
    }

    /** The tier naming quality level {@code quality} (clamped into range), or empty when no tiers. */
    public Optional<QualityTier> tierFor(int quality) {
        if (tiers.isEmpty()) {
            return Optional.empty();
        }
        int index = Math.max(0, Math.min(tiers.size() - 1, quality));
        return Optional.of(tiers.get(index));
    }

    /** Loads (or reloads) the tier list. Returns true when it parsed cleanly. */
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
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }

        List<QualityTier> parsed = new ArrayList<>();
        int skipped = 0;
        List<?> rawList = yaml.getList(ROOT);
        if (rawList != null) {
            for (Object element : rawList) {
                if (!(element instanceof ConfigurationSection) && !(element instanceof java.util.Map)) {
                    skipped++;
                    continue;
                }
                String name;
                String color;
                if (element instanceof ConfigurationSection section) {
                    name = section.getString("name");
                    color = section.getString("color", "");
                } else {
                    java.util.Map<?, ?> map = (java.util.Map<?, ?>) element;
                    name = map.get("name") == null ? null : String.valueOf(map.get("name"));
                    color = map.get("color") == null ? "" : String.valueOf(map.get("color"));
                }
                if (name == null || name.isBlank()) {
                    skipped++;
                    continue;
                }
                parsed.add(new QualityTier(name.trim(), color));
            }
        }
        this.tiers = List.copyOf(parsed);
        log.info("[" + PATH + "] loaded " + parsed.size() + " quality tier(s)"
                + (skipped > 0 ? " (" + skipped + " skipped)" : "") + " OK");
        return skipped == 0;
    }
}
