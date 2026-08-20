package com.trinityforge.config.domains;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.LoadableConfig;
import com.trinityforge.mobs.MobProfile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code combat/mob-profiles.yml}: the per-mob defender profiles the EliteMobs importer
 * generates and the EliteMobs fork stamps onto mob PDC at spawn (concern: bulk-convert distributed
 * EliteMobs mobs). The {@code profiles:} section is open-ended (mob ids = EliteMobs file names).
 * Malformed entries are skipped with a warning; the rest load. Informational keys written by the
 * importer ({@code source-name}, {@code entity-type}) are ignored at runtime.
 *
 * <p>{@link #parse(ConfigurationSection, Logger)} is separated from {@link #load(Plugin)} so the
 * parse round-trips against the importer's output in unit tests without a Plugin.
 */
public final class MobProfileConfig implements LoadableConfig {

    public static final String PATH = "combat/mob-profiles.yml";
    private static final String ROOT = "profiles";
    private static final String ARMOR_STRENGTH = "armor-strength";
    private static final String MAX_HEALTH = "max-health";
    private static final String DYNAMIC = "dynamic";

    private volatile Map<String, MobProfile> profiles = Map.of();

    public Optional<MobProfile> profile(String id) {
        return Optional.ofNullable(profiles.get(id));
    }

    public Map<String, MobProfile> all() {
        return profiles;
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
        // 構文エラー時は直前に成功ロード済みのprofiles(初回失敗時はMap.of())を維持しfalseを返す。
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }
        ParseResult result = parse(yaml.getConfigurationSection(ROOT), log);
        this.profiles = result.profiles();

        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + result.profiles().size() + " mob profile(s), "
                    + result.skipped() + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + result.profiles().size() + " mob profile(s) OK");
        return true;
    }

    /** Pure parse of the {@code profiles:} section. Invalid entries are skipped, not fatal. */
    static ParseResult parse(ConfigurationSection root, Logger log) {
        Map<String, MobProfile> parsed = new LinkedHashMap<>();
        int skipped = 0;
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(id);
                if (entry == null) {
                    log.warning("[" + PATH + "] profile '" + id + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                try {
                    int level = entry.getInt("level", 0);
                    String theme = blankToNull(entry.getString("dungeon-theme"));
                    double armorStrength = entry.getDouble(ARMOR_STRENGTH, 0.0);
                    DefenseStats physical = defense(entry.getConfigurationSection("physical"), armorStrength);
                    DefenseStats magical = defense(entry.getConfigurationSection("magical"), armorStrength);
                    AttackStats attack = attack(entry.getConfigurationSection("attack"));
                    // 0 (or absent) = no TrinityForge-driven HP → the EliteMobs fork keeps its own HP.
                    double maxHealth = entry.getDouble(MAX_HEALTH, 0.0);
                    boolean dynamic = entry.getBoolean(DYNAMIC, false);
                    parsed.put(id, new MobProfile(id, level, theme, physical, magical, attack, maxHealth,
                            dynamic));
                } catch (IllegalArgumentException ex) {
                    log.warning("[" + PATH + "] profile '" + id + "' invalid (" + ex.getMessage() + "); skipped");
                    skipped++;
                }
            }
        }
        return new ParseResult(Map.copyOf(parsed), skipped);
    }

    private static DefenseStats defense(ConfigurationSection section, double armorStrength) {
        if (section == null) {
            return new DefenseStats(0.0, 0.0, 0.0, 0.0, armorStrength);
        }
        // Preserve authored values here. The shared combat choke applies defense.min/max-* once,
        // after every item/perk/addon/profile contribution has been combined.
        return new DefenseStats(
                section.getDouble("defense-rate", 0.0),
                section.getDouble("resistance", 0.0),
                section.getDouble("damage-reduction", 0.0),
                section.getDouble("flat-defense", 0.0),
                armorStrength);
    }

    /**
     * Attacker-side stats ({@code attack:} block, same keys as {@code combat/mob-types.yml}).
     * Absent section = unconfigured attack so the mob keeps its vanilla/EliteMobs damage.
     * Authored zero and negative values are retained; damage-modifier alone defaults to neutral 1.0.
     */
    private static AttackStats attack(ConfigurationSection section) {
        if (section == null) {
            return AttackStats.plain(0);
        }
        return new AttackStats(
                section.getDouble("attack-power", 0.0),
                section.getDouble("flat-bonus-damage", 0.0),
                section.getDouble("percent-bonus-damage", 0.0),
                section.getDouble("crit-chance", 0.0),
                section.getDouble("crit-damage", 0.0),
                section.getDouble("penetration", 0.0),
                section.getDouble("damage-modifier", 1.0),
                section.getDouble("fixed-damage", 0.0),
                // 2026-08-02: mob-types.ymlと同じ attack.magic-ratio。既定0.0=完全物理。
                section.getDouble("magic-ratio", 0.0));
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    /** Parse outcome: the immutable profile map and how many entries were skipped. */
    record ParseResult(Map<String, MobProfile> profiles, int skipped) {
    }
}
