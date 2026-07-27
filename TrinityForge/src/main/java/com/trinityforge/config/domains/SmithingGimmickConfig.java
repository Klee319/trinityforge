package com.trinityforge.config.domains;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/smithing-gimmick.yml}: tuning for the鍛冶ツリー「精錬速度」
 * ({@code feature:furnace-smelt-speed}, smithing.yml A-1/A-2/A-3)と「精錬ボーナス」
 * ({@code feature:furnace-smelt-bonus}, B-1/B-2/B-3)の唯一の設定値 — ホッパー自動投入時の減衰係数
 * ({@link #autoModeMultiplier()})。実際の%はスキルツリーの {@code dedicated-effects value} が
 * そのまま使われる({@link com.trinityforge.config.domains.DedicatedEffectsConfig#valueMax}) ので
 * tierテーブルは持たない。Same raw-YAML loader style as {@link FoodGimmickConfig}。
 */
public final class SmithingGimmickConfig {

    public static final String PATH = "stats/smithing-gimmick.yml";

    private static final double DEFAULT_AUTO_MODE_MULTIPLIER = 0.25;

    private volatile double autoModeMultiplier = DEFAULT_AUTO_MODE_MULTIPLIER;

    /**
     * ホッパー自動投入(auto)時に精錬速度/精錬ボーナスへ掛ける減衰係数。{@code 0.0}=無効化、
     * {@code 1.0}=手動と同じ効果。既定 {@value #DEFAULT_AUTO_MODE_MULTIPLIER}(醸造EXPのauto_multと同じ)。
     */
    public double autoModeMultiplier() {
        return autoModeMultiplier;
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

        this.autoModeMultiplier = clamp01(
                yaml.getDouble("auto-mode-multiplier", DEFAULT_AUTO_MODE_MULTIPLIER), log);

        log.info("[" + PATH + "] loaded (auto-mode-multiplier=" + this.autoModeMultiplier + ") OK");
        return true;
    }

    private static double clamp01(double raw, Logger log) {
        if (!Double.isFinite(raw) || raw < 0.0 || raw > 1.0) {
            log.warning("[" + PATH + "] 'auto-mode-multiplier' must be within [0,1] (was " + raw
                    + "); using default " + DEFAULT_AUTO_MODE_MULTIPLIER);
            return DEFAULT_AUTO_MODE_MULTIPLIER;
        }
        return raw;
    }
}
