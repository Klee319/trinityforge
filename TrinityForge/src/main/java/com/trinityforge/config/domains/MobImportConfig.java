package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.mobs.ConversionPolicy;
import com.trinityforge.mobs.ConversionPolicy.DefenseRamp;
import com.trinityforge.mobs.ConversionPolicy.LevelSource;
import com.trinityforge.mobs.ConversionPolicy.Ramp;
import com.trinityforge.mobs.RampParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code combat/mob-import.yml}: the {@link ConversionPolicy} the EliteMobs importer
 * applies (concern: bulk-convert distributed EliteMobs mobs). Since EliteMobs files carry no
 * defense fields, every defender value is synthesized from {@code base + perLevel * level} here, so
 * the whole conversion is tunable in config with no hardcoded balance. A reload re-tunes the policy
 * for the next import.
 */
public final class MobImportConfig implements LoadableConfig {

    public static final String PATH = "combat/mob-import.yml";

    /** Shipped default for {@code unknown-mobs.synthesize} (see {@link #synthesizeUnknown()}). */
    static final boolean DEFAULT_SYNTHESIZE_UNKNOWN = true;

    private volatile ConversionPolicy policy = defaultPolicy();
    private volatile boolean synthesizeUnknown = DEFAULT_SYNTHESIZE_UNKNOWN;

    public ConversionPolicy policy() {
        return policy;
    }

    /**
     * True when an EliteMobs custom boss that has NO entry in {@code combat/mob-profiles.yml} should
     * still get a profile, synthesized at spawn time from this policy and the mob's runtime level
     * (2026-07-26 「無料DL枠ダンジョンの敵を設定できない」修正).
     *
     * <p>Rationale: the converter never reads a boss file's own numbers — every defender/attacker
     * value comes from this policy's ramps evaluated at the mob's level (see
     * {@code EliteMobsMobMapping#rebuildAt}). So an un-imported mob can be derived at runtime with the
     * exact same result {@code /trinityforge importmobs} would have baked, and newly downloaded
     * content packs no longer silently fall outside TrinityForge (no PDC stamp ⇒
     * {@code combat/mob-overrides.yml} could not target them at all).
     *
     * <p>Set to {@code false} to restore the pre-fix behaviour ("only mobs listed in
     * mob-profiles.yml are TrinityForge-driven").
     */
    public boolean synthesizeUnknown() {
        return synthesizeUnknown;
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
        // 構文エラー時は直前に成功ロード済みのpolicy(初回失敗時はdefaultPolicy())を維持しfalseを
        // 返す(下のRuntimeExceptionハンドラとは異なり、ここではpolicyを上書きしない)。
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }

        try {
            this.policy = parse(yaml, log);
            this.synthesizeUnknown = parseSynthesizeUnknown(yaml);
            log.info("[" + PATH + "] loaded conversion policy OK");
            return true;
        } catch (RuntimeException ex) {
            this.policy = defaultPolicy();
            this.synthesizeUnknown = DEFAULT_SYNTHESIZE_UNKNOWN;
            log.warning("[" + PATH + "] invalid (" + ex.getMessage() + "); default policy applied");
            return false;
        }
    }

    // Package-private (not private) so unit tests can round-trip the parse without a live Plugin,
    // mirroring MobProfileConfig.parse / DungeonThemeConfig.parse.
    static ConversionPolicy parse(ConfigurationSection root, Logger log) {
        LevelSource source = parseSource(root.getString("level.source"), log);
        int fixed = root.getInt("level.fixed", 1);
        int def = root.getInt("level.default", 1);
        String theme = root.getString("theme.default", "");
        ConversionPolicy.Variance variance = new ConversionPolicy.Variance(
                root.getDouble("variance.hp", 0.0),
                root.getDouble("variance.attack", 0.0));
        return new ConversionPolicy(
                source, fixed, def, theme,
                RampParser.defenseRamp(root, "physical", log, PATH),
                RampParser.defenseRamp(root, "magical", log, PATH),
                RampParser.ramp(root, "armor-strength", log, PATH),
                RampParser.attackRamp(root, "attack", log, PATH),
                RampParser.ramp(root, "max-health", log, PATH),
                variance);
    }

    /**
     * Reads {@code unknown-mobs.synthesize}. Package-private so the parse can be unit-tested without a
     * live Plugin, mirroring {@link #parse}. An absent key keeps
     * {@link #DEFAULT_SYNTHESIZE_UNKNOWN} (back-compat for configs written before this key existed).
     */
    static boolean parseSynthesizeUnknown(ConfigurationSection root) {
        return root.getBoolean("unknown-mobs.synthesize", DEFAULT_SYNTHESIZE_UNKNOWN);
    }

    static LevelSource parseSource(String raw, Logger log) {
        if (raw == null || raw.isBlank()) {
            return LevelSource.ELITEMOBS;
        }
        try {
            return LevelSource.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            log.warning("[" + PATH + "] unknown level.source '" + raw + "'; defaulting to ELITEMOBS");
            return LevelSource.ELITEMOBS;
        }
    }

    private static ConversionPolicy defaultPolicy() {
        Ramp zero = new Ramp(0.0, 0.0);
        DefenseRamp zeroDefense = new DefenseRamp(zero, zero, zero, zero);
        return new ConversionPolicy(LevelSource.ELITEMOBS, 1, 1, "", zeroDefense, zeroDefense, zero,
                ConversionPolicy.AttackRamp.ZERO, zero, ConversionPolicy.Variance.ZERO);
    }
}
