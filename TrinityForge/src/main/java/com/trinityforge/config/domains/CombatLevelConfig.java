package com.trinityforge.config.domains;

import com.trinityforge.progression.CombatLevelModel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code progression/combat-level.yml}, the combat-level mapping curve
 * (DUNGEON_SPEC 2 / ADDON_INTEGRATION_SPEC 1.5). The {@code skills:} section is an open-ended
 * weight table (arbitrary skill keys) so it is parsed directly rather than via a flat schema;
 * the {@code curve:} section holds the scalar tuning knobs.
 *
 * <p>Exposes an immutable {@link CombatLevelModel} snapshot, rebuilt atomically on each load so
 * {@code /trinityforge reload} re-balances the mapping with no code change.
 */
public final class CombatLevelConfig {

    public static final String PATH = "progression/combat-level.yml";
    private static final String SKILLS = "skills";
    private static final String PILLARS = "pillars";
    private static final String CURVE = "curve";
    private static final String CACHE = "cache";

    private static final double DEFAULT_SCALE = 1.0;
    private static final int DEFAULT_MIN_LEVEL = 0;
    private static final int DEFAULT_MAX_LEVEL = 100;

    /** Default pillars (max-of-top-N). A pure specialist scores top1/1.0; two, three and four-skill
     *  builds score sum(top2)/1.5, sum(top3)/2.1 and sum(top4)/2.8 respectively. Used when
     *  {@code pillars:} is missing or every entry is invalid, so the model always has a rule. */
    private static final List<CombatLevelModel.PillarRule> DEFAULT_PILLARS =
            List.of(new CombatLevelModel.PillarRule(1, 1.0),
                    new CombatLevelModel.PillarRule(2, 1.5),
                    new CombatLevelModel.PillarRule(3, 2.1),
                    new CombatLevelModel.PillarRule(4, 2.8));

    /** Short per-player TTL for progression/skill-level read caches. 0 disables caching; the range
     *  keeps a misconfigured value from either doing nothing
     *  useful (too small) or serving skill levels that are stale for minutes (too large). */
    private static final int DEFAULT_CACHE_TTL_SECONDS = 3;
    private static final int MIN_CACHE_TTL_SECONDS = 0;
    private static final int MAX_CACHE_TTL_SECONDS = 300;

    private final String resourcePath = PATH;
    private volatile CombatLevelModel model =
            new CombatLevelModel(Map.of(), DEFAULT_PILLARS,
                    DEFAULT_SCALE, DEFAULT_MIN_LEVEL, DEFAULT_MAX_LEVEL);
    private volatile long cacheTtlMillis = DEFAULT_CACHE_TTL_SECONDS * 1000L;

    public CombatLevelModel model() {
        return model;
    }

    /**
     * TTL (ms) for progression/skill-level read caches. 0 disables caching.
     * Callers building a cache should
     * read this live (e.g. via a {@code LongSupplier}) rather than snapshotting it, so
     * {@code /trinityforge reload} picks up a changed TTL without recreating the source.
     */
    public long cacheTtlMillis() {
        return cacheTtlMillis;
    }

    public String resourcePath() {
        return resourcePath;
    }

    /** Loads (or reloads) the mapping. Returns true when every weight entry parsed cleanly. */
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), resourcePath);
        if (!file.exists()) {
            plugin.saveResource(resourcePath, false);
        }

        // loadConfiguration(File) は構文エラーを握り潰して空configを返すため自前でload()する。
        // 構文エラー時は直前に成功ロード済みのmodel(初回失敗時はコンストラクタのデフォルト)を
        // 維持しfalseを返す。
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + resourcePath + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }

        Map<String, Double> weights = new LinkedHashMap<>();
        int skipped = 0;
        ConfigurationSection skills = yaml.getConfigurationSection(SKILLS);
        if (skills != null) {
            for (String key : skills.getKeys(false)) {
                if (!skills.isDouble(key) && !skills.isInt(key)) {
                    log.warning("[" + resourcePath + "] skill weight '" + key + "' is not numeric; skipped");
                    skipped++;
                    continue;
                }
                // .nan / .inf parse as valid doubles via SnakeYAML but would poison the combat-level
                // math (a NaN weighted level ranks unpredictably, an Inf pins every player to max-level).
                // Require a finite weight and skip the entry otherwise, keeping the rest of the table.
                double weight = skills.getDouble(key);
                if (!Double.isFinite(weight)) {
                    log.warning("[" + resourcePath + "] skill weight '" + key + "' is not finite ("
                            + weight + "); skipped");
                    skipped++;
                    continue;
                }
                weights.put(key, weight);
            }
        }

        // pillars: list of { top: <int>, divisor: <double> }. Each scores sum(top-N highest
        // weighted levels)/divisor; the combat level takes the max score (LD-7). Missing or
        // all-invalid falls back to DEFAULT_PILLARS so the model is never rule-less.
        List<CombatLevelModel.PillarRule> pillars = new ArrayList<>();
        int pillarSkipped = 0;
        for (Map<?, ?> raw : yaml.getMapList(PILLARS)) {
            Object topObj = raw.get("top");
            Object divisorObj = raw.get("divisor");
            if (!(topObj instanceof Number) || !(divisorObj instanceof Number)) {
                log.warning("[" + resourcePath + "] pillar entry " + raw
                        + " must have numeric 'top' and 'divisor'; skipped");
                pillarSkipped++;
                continue;
            }
            try {
                pillars.add(new CombatLevelModel.PillarRule(
                        ((Number) topObj).intValue(), ((Number) divisorObj).doubleValue()));
            } catch (IllegalArgumentException invalid) {
                log.warning("[" + resourcePath + "] invalid pillar " + raw + " ("
                        + invalid.getMessage() + "); skipped");
                pillarSkipped++;
            }
        }
        if (pillars.isEmpty()) {
            if (pillarSkipped > 0) {
                log.warning("[" + resourcePath + "] no valid 'pillars:' entries; using defaults "
                        + "(top1/1.0, top2/1.5, top3/2.1, top4/2.8)");
            }
            pillars = DEFAULT_PILLARS;
        }

        double scale = yaml.getDouble(CURVE + ".scale", DEFAULT_SCALE);
        int minLevel = yaml.getInt(CURVE + ".min-level", DEFAULT_MIN_LEVEL);
        int maxLevel = yaml.getInt(CURVE + ".max-level", DEFAULT_MAX_LEVEL);

        boolean cacheTtlValid = true;
        int cacheTtlSeconds = yaml.getInt(CACHE + ".ttl-seconds", DEFAULT_CACHE_TTL_SECONDS);
        if (cacheTtlSeconds < MIN_CACHE_TTL_SECONDS || cacheTtlSeconds > MAX_CACHE_TTL_SECONDS) {
            log.warning("[" + resourcePath + "] cache.ttl-seconds (" + cacheTtlSeconds
                    + ") out of range [" + MIN_CACHE_TTL_SECONDS + ", " + MAX_CACHE_TTL_SECONDS
                    + "]; using default " + DEFAULT_CACHE_TTL_SECONDS + "s");
            cacheTtlSeconds = DEFAULT_CACHE_TTL_SECONDS;
            cacheTtlValid = false;
        }

        try {
            this.model = new CombatLevelModel(weights, pillars, scale, minLevel, maxLevel);
        } catch (IllegalArgumentException invalid) {
            log.warning("[" + resourcePath + "] invalid curve (" + invalid.getMessage()
                    + "); keeping previous values");
            return false;
        }
        this.cacheTtlMillis = cacheTtlSeconds * 1000L;

        if (weights.isEmpty()) {
            log.warning("[" + resourcePath + "] 'skills:' section is missing or empty; every player "
                    + "will map to min-level (" + minLevel + "). Check the skill weight table.");
            return false;
        }
        if (skipped > 0) {
            log.warning("[" + resourcePath + "] loaded " + weights.size() + " skill weight(s), "
                    + skipped + " skipped");
            return false;
        }
        if (pillarSkipped > 0) {
            log.warning("[" + resourcePath + "] " + pillarSkipped + " pillar entry(ies) skipped");
            return false;
        }
        if (!cacheTtlValid) {
            return false;
        }
        log.info("[" + resourcePath + "] loaded " + weights.size() + " skill weight(s) OK");
        return true;
    }
}
