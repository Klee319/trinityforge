package com.trinityforge.config.domains;

import com.trinityforge.integration.ars.ArsProgressionBridge;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/craft-quality.yml} (ITEM_ECONOMY_SPEC 5.2d): how a CRAFTED item's quality is
 * derived (fished items moved to the rod-driven {@link com.trinityforge.listeners.FishingQualityListener}
 * / {@code stats/fishing-gimmick.yml}, so {@code fishing.*} no longer lives here). {@link #categorySkill()} is a
 * fixed (hard-coded) item stat-category -> production skill map — the crafting production skill lineup
 * ({@code weapon/armor/tool -> SMITHING}, {@code ars-gear -> ARS_SMITHING}) is a stable game rule, not an
 * operator knob — and {@code mode.*} tunes level->quality. (Skill EXP gain rates, e.g. ARS_SMITHING craft
 * EXP, live in {@code stats/skill-exp.yml} / {@link SkillExpConfig} instead.)
 */
public final class CraftQualityConfig {

    public static final String PATH = "stats/craft-quality.yml";

    // 固定(ハードコード)の カテゴリ -> 生産スキル 対応。運用上変わらないゲームルールのため設定化しない。
    private static final Map<String, String> CATEGORY_SKILL = Map.of(
            "weapon", "SMITHING",
            "armor", "SMITHING",
            "tool", "SMITHING",
            "ars-gear", ArsProgressionBridge.ARS_SMITHING);

    private volatile int skillLevelsPerQuality = 10;
    private volatile int baseQuality = 0;
    private volatile boolean dropEnabled = true;
    private volatile int dropStrengthPerQuality = 10;
    private volatile int dropBaseQuality = 0;

    /** Fixed item stat-category -> production skill map (not config-driven; see class javadoc). */
    public Map<String, String> categorySkill() {
        return CATEGORY_SKILL;
    }

    public int skillLevelsPerQuality() {
        return skillLevelsPerQuality;
    }

    public int baseQuality() {
        return baseQuality;
    }

    /**
     * Whether mob-drop quality is driven by enemy strength: a normal (bell) distribution whose mode
     * shifts up with the elite level (see {@code stats/craft-quality.yml drop}). When {@code false} the
     * EliteMobs fork keeps a uniform-random placeholder over the valid quality range.
     */
    public boolean dropEnabled() {
        return dropEnabled;
    }

    /** Enemy levels per +1 mob-drop quality mode (integer division; ≤0 pins the mode at the base). */
    public int dropStrengthPerQuality() {
        return dropStrengthPerQuality;
    }

    /** Mob-drop quality mode for an enemy of strength 0 (or a non-elite). */
    public int dropBaseQuality() {
        return dropBaseQuality;
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

        this.skillLevelsPerQuality = yaml.getInt("mode.skill-levels-per-quality", 10);
        this.baseQuality = yaml.getInt("mode.base-quality", 0);
        this.dropEnabled = yaml.getBoolean("drop.enabled", true);
        this.dropStrengthPerQuality = yaml.getInt("drop.strength-per-quality", 10);
        this.dropBaseQuality = yaml.getInt("drop.base-quality", 0);
        log.info("[" + PATH + "] loaded OK");
        return true;
    }
}
