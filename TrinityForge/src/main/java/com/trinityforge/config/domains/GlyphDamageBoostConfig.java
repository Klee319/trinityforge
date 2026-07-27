package com.trinityforge.config.domains;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/glyph-damage-boost.yml}: which glyph ids {@code glyph_damage_multiplier_bonus}
 * (2026-07-25 ars_magic.yml B-3「害悪強化」) applies to. Config-driven so the underlying stat/API stays
 * generic — no glyph id (e.g. {@code harm}) is hardcoded in Java. Adding a new glyph to the boost only
 * requires editing this file; {@link com.trinityforge.TrinityForge#glyphDamageMultiplier} reads it.
 */
public final class GlyphDamageBoostConfig {

    public static final String PATH = "stats/glyph-damage-boost.yml";

    private volatile Set<String> boostedGlyphs = Set.of();

    /** True when {@code glyphId} (case-insensitive) is configured to receive the damage-multiplier bonus. */
    public boolean isBoosted(String glyphId) {
        if (glyphId == null) return false;
        return boostedGlyphs.contains(glyphId.trim().toLowerCase(Locale.ROOT));
    }

    /** Loads (or reloads) the config. Returns true when it parsed cleanly. */
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

        List<String> raw = yaml.getStringList("boosted-glyphs");
        Set<String> parsed = new LinkedHashSet<>();
        for (String id : raw) {
            if (id == null || id.isBlank()) continue;
            parsed.add(id.trim().toLowerCase(Locale.ROOT));
        }
        this.boostedGlyphs = Set.copyOf(parsed);

        log.info("[" + PATH + "] loaded " + this.boostedGlyphs.size() + " boosted glyph id(s) OK");
        return true;
    }
}
