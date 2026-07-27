package com.trinityforge.config.domains;

import com.trinityforge.skilltree.effects.TierTable;
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

/**
 * Loader for {@code stats/farming-gimmick.yml}: tuning for the農業/畜産スキルツリーflag系
 * dedicated-effect consumers that have no existing config home ({@code area-harvest},
 * {@code animal-damage-4x}, {@code bee-no-aggro} — see the {@code dedicated-effects:} field on each node in {@code skilltree/*.yml}).
 * 養蜂の幸運確率({@code hive_harvest_fortune})は装備+perk合算ステータスとして別経路
 * ({@code PlayerStatAggregator})で持つため、このconfigには含まない。Same raw-YAML loader style as
 * {@link MiningGimmickConfig}/{@link WoodcuttingGimmickConfig}。
 */
public final class FarmingGimmickConfig {

    public static final String PATH = "stats/farming-gimmick.yml";

    private static final int DEFAULT_AREA_HARVEST_RADIUS = 1;
    private static final double DEFAULT_ANIMAL_DAMAGE_MULTIPLIER = 4.0;
    private static final double DEFAULT_BEE_CALM_RADIUS = 8.0;

    private volatile int areaHarvestRadius = DEFAULT_AREA_HARVEST_RADIUS;
    private volatile double animalDamageMultiplier = DEFAULT_ANIMAL_DAMAGE_MULTIPLIER;
    private volatile double beeCalmRadius = DEFAULT_BEE_CALM_RADIUS;
    /** {@code area-harvest.tiers.<tier>.radius} (2026-07-25 §1)。未定義ならグローバルscalarへ完全後方互換。 */
    private volatile TierTable<Integer> areaHarvestTiers = TierTable.empty();

    /** {@code area-harvest} の範囲収穫半径(ブロック)。1=3x3(8マス追加)。tiers未定義時のグローバル既定値。 */
    public int areaHarvestRadius() {
        return areaHarvestRadius;
    }

    /**
     * {@code area-harvest} の範囲収穫半径を {@code tier}(プレイヤーの解放済み最高tier)で解決する。
     * {@code area-harvest.tiers} が未定義、または {@code tier} 未満の行しかない場合は
     * {@link #areaHarvestRadius()}(グローバルscalar)へ完全後方互換フォールバックする。
     */
    public int areaHarvestRadius(int tier) {
        return areaHarvestTiers.resolve(tier).orElse(areaHarvestRadius);
    }

    /** {@code animal-damage-4x} の動物への与ダメージ倍率。 */
    public double animalDamageMultiplier() {
        return animalDamageMultiplier;
    }

    /** {@code bee-no-aggro} 採取時、周囲何ブロック以内のハチの怒りを鎮めるか(要調整・ベストエフォート)。 */
    public double beeCalmRadius() {
        return beeCalmRadius;
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

        this.areaHarvestRadius = clampPositiveInt(
                yaml.getInt("area-harvest.radius", DEFAULT_AREA_HARVEST_RADIUS),
                "area-harvest.radius", DEFAULT_AREA_HARVEST_RADIUS, log);
        this.animalDamageMultiplier = clampPositiveDouble(
                yaml.getDouble("animal-damage-4x.multiplier", DEFAULT_ANIMAL_DAMAGE_MULTIPLIER),
                "animal-damage-4x.multiplier", DEFAULT_ANIMAL_DAMAGE_MULTIPLIER, log);
        this.beeCalmRadius = clampPositiveDouble(
                yaml.getDouble("bee-no-aggro.calm-radius", DEFAULT_BEE_CALM_RADIUS),
                "bee-no-aggro.calm-radius", DEFAULT_BEE_CALM_RADIUS, log);
        this.areaHarvestTiers = parseAreaHarvestTiers(yaml.getConfigurationSection("area-harvest.tiers"), log);

        log.info("[" + PATH + "] loaded OK");
        return true;
    }

    /**
     * {@code area-harvest.tiers: {<tier>: {radius: N}}} (2026-07-25 §1)。Absent/empty section yields
     * {@link TierTable#empty()} (省略時は完全後方互換)。
     */
    private static TierTable<Integer> parseAreaHarvestTiers(ConfigurationSection section, Logger log) {
        if (section == null) {
            return TierTable.empty();
        }
        Map<Integer, Integer> rows = new LinkedHashMap<>();
        for (String tierKey : section.getKeys(false)) {
            int tier;
            try {
                tier = Integer.parseInt(tierKey.trim());
                if (tier <= 0) {
                    log.warning("[" + PATH + "] 'area-harvest.tiers." + tierKey + "' key must be a positive integer; skipped");
                    continue;
                }
            } catch (NumberFormatException ex) {
                log.warning("[" + PATH + "] 'area-harvest.tiers." + tierKey + "' key is not an integer; skipped");
                continue;
            }
            ConfigurationSection row = section.getConfigurationSection(tierKey);
            int radius = row == null ? 0 : row.getInt("radius", 0);
            if (radius <= 0) {
                log.warning("[" + PATH + "] 'area-harvest.tiers." + tierKey + ".radius' must be > 0; row skipped");
                continue;
            }
            rows.put(tier, radius);
        }
        return TierTable.of(rows);
    }

    /** Non-finite/non-positive guard for a count value: falls back to {@code fallback}, never throws. */
    private static int clampPositiveInt(int raw, String key, int fallback, Logger log) {
        if (raw <= 0) {
            log.warning("[" + PATH + "] '" + key + "' must be > 0 (was " + raw + "); using default " + fallback);
            return fallback;
        }
        return raw;
    }

    /** Non-finite/non-positive guard for a multiplier/radius value: falls back to {@code fallback}. */
    private static double clampPositiveDouble(double raw, String key, double fallback, Logger log) {
        if (!Double.isFinite(raw) || raw <= 0.0) {
            log.warning("[" + PATH + "] '" + key + "' must be > 0 (was " + raw + "); using default " + fallback);
            return fallback;
        }
        return raw;
    }
}
