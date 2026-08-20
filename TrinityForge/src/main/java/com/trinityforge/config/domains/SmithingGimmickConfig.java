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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/smithing-gimmick.yml}: tuning for the鍛冶ツリー「精錬速度」
 * ({@code feature:furnace-smelt-speed}, smithing.yml A-1/A-2/A-3)と「精錬ボーナス」
 * ({@code feature:furnace-smelt-bonus}, B-1/B-2/B-3)の設定値 — ホッパー自動投入時の減衰係数
 * ({@link #autoModeMultiplier()})に加え、2026-07-28(数値のギミックyml集約)から
 * {@code furnace-smelt.speed/bonus} の tierテーブル({@link #smeltSpeedPercent(int)} /
 * {@link #smeltBonusPercent(int)})も持つ。スキルツリーのノードは tier番号(1/2/3)だけを持ち
 * ({@code feature:furnace-smelt-speed/bonus} は {@code FeatureEffectParam.SCALE})、実際の%は
 * ここで tier→% を解決する。解決規則: tierの完全一致 → 無ければ tier以下で最大の行 → それも無ければ
 * グローバルの {@code percent}(既定フォールバック)。完全一致しなかった場合は移行事故検知のため
 * tierごとに一度だけ WARNING を出す(旧versionのsmithing.ymlが配備先に残っていると、value 10/20/30が
 * 全てtier3の行へ無言でフォールバックし精錬ボーナスが無言強化される事故を防ぐため)。
 * Same raw-YAML loader style as {@link FoodGimmickConfig}。
 */
public final class SmithingGimmickConfig {

    public static final String PATH = "stats/smithing-gimmick.yml";

    private static final double DEFAULT_AUTO_MODE_MULTIPLIER = 0.25;
    private static final double DEFAULT_SMELT_PERCENT = 10.0;

    private volatile double autoModeMultiplier = DEFAULT_AUTO_MODE_MULTIPLIER;
    private volatile double smeltSpeedPercentDefault = DEFAULT_SMELT_PERCENT;
    private volatile TierTable<Double> smeltSpeedTiers = TierTable.empty();
    private volatile double smeltBonusPercentDefault = DEFAULT_SMELT_PERCENT;
    private volatile TierTable<Double> smeltBonusTiers = TierTable.empty();
    private volatile Logger log;
    /** 「tierの完全一致が取れなかった」WARNINGを同じtierに対して1度だけ出すための抑制セット。reload毎に作り直す。 */
    private volatile Set<String> nonExactTierWarned = ConcurrentHashMap.newKeySet();

    /**
     * ホッパー自動投入(auto)時に精錬速度/精錬ボーナスへ掛ける減衰係数。{@code 0.0}=無効化、
     * {@code 1.0}=手動と同じ効果。既定 {@value #DEFAULT_AUTO_MODE_MULTIPLIER}(醸造EXPのauto_multと同じ)。
     */
    public double autoModeMultiplier() {
        return autoModeMultiplier;
    }

    /**
     * 精錬速度%を {@code tier}(smithing.yml A-1/A-2/A-3の {@code feature:furnace-smelt-speed} value、
     * {@code DedicatedEffectsConfig#valueMax} で解決したプレイヤーの最高tier)で解決する。
     */
    public double smeltSpeedPercent(int tier) {
        return resolveTierPercent(smeltSpeedTiers, tier, smeltSpeedPercentDefault, "furnace-smelt.speed");
    }

    /** {@link #smeltSpeedPercent(int)}と同じ規則で解決する精錬ボーナス%(B-1/B-2/B-3)。 */
    public double smeltBonusPercent(int tier) {
        return resolveTierPercent(smeltBonusTiers, tier, smeltBonusPercentDefault, "furnace-smelt.bonus");
    }

    private double resolveTierPercent(TierTable<Double> table, int tier, double fallback, String fieldLabel) {
        Optional<Double> resolved = table.resolve(tier);
        if (resolved.isEmpty()) {
            warnOnce(fieldLabel, tier, "tier " + tier + " に一致/以下の行が無く、グローバル既定値("
                    + fallback + "%)を使用します");
            return fallback;
        }
        Optional<Integer> resolvedKey = table.resolvedKey(tier);
        if (resolvedKey.isPresent() && resolvedKey.get() != tier) {
            warnOnce(fieldLabel, tier, "tier " + tier + " の完全一致行が無く、tier " + resolvedKey.get()
                    + " の行へフォールバックしました(旧versionのsmithing.ymlが配備先に残っていないか確認してください)");
        }
        return resolved.get();
    }

    private void warnOnce(String fieldLabel, int tier, String message) {
        Logger l = this.log;
        if (l == null || !nonExactTierWarned.add(fieldLabel + ":" + tier)) {
            return;
        }
        l.warning("[" + PATH + "] '" + fieldLabel + "' " + message);
    }

    /** Loads (or reloads) the config. Returns true when it parsed cleanly. */
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        this.log = log;
        this.nonExactTierWarned = ConcurrentHashMap.newKeySet();
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

        ConfigurationSection speed = yaml.getConfigurationSection("furnace-smelt.speed");
        this.smeltSpeedPercentDefault = clampPositive(
                speed == null ? DEFAULT_SMELT_PERCENT : speed.getDouble("percent", DEFAULT_SMELT_PERCENT),
                "furnace-smelt.speed.percent", log);
        this.smeltSpeedTiers = parsePercentTiers(
                speed == null ? null : speed.getConfigurationSection("tiers"), "furnace-smelt.speed.tiers", log);

        ConfigurationSection bonus = yaml.getConfigurationSection("furnace-smelt.bonus");
        this.smeltBonusPercentDefault = clampPositive(
                bonus == null ? DEFAULT_SMELT_PERCENT : bonus.getDouble("percent", DEFAULT_SMELT_PERCENT),
                "furnace-smelt.bonus.percent", log);
        this.smeltBonusTiers = parsePercentTiers(
                bonus == null ? null : bonus.getConfigurationSection("tiers"), "furnace-smelt.bonus.tiers", log);

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

    private static double clampPositive(double raw, String fieldLabel, Logger log) {
        if (!Double.isFinite(raw) || raw < 0.0) {
            log.warning("[" + PATH + "] '" + fieldLabel + "' must be >= 0 (was " + raw
                    + "); using default " + DEFAULT_SMELT_PERCENT);
            return DEFAULT_SMELT_PERCENT;
        }
        return raw;
    }

    /**
     * {@code <section>.tiers: {<tier>: {percent: N}}} を解析する。Absent/empty section は
     * {@link TierTable#empty()}(=グローバル既定値へ完全後方互換)を返す。
     */
    private static TierTable<Double> parsePercentTiers(ConfigurationSection section, String fieldLabel, Logger log) {
        if (section == null) {
            return TierTable.empty();
        }
        Map<Integer, Double> rows = new LinkedHashMap<>();
        for (String tierKey : section.getKeys(false)) {
            int tier;
            try {
                tier = Integer.parseInt(tierKey.trim());
                if (tier <= 0) {
                    log.warning("[" + PATH + "] '" + fieldLabel + "." + tierKey + "' key must be a positive integer; skipped");
                    continue;
                }
            } catch (NumberFormatException ex) {
                log.warning("[" + PATH + "] '" + fieldLabel + "." + tierKey + "' key is not an integer; skipped");
                continue;
            }
            ConfigurationSection row = section.getConfigurationSection(tierKey);
            double percent = row == null ? -1.0 : row.getDouble("percent", -1.0);
            if (!Double.isFinite(percent) || percent < 0.0) {
                log.warning("[" + PATH + "] '" + fieldLabel + "." + tierKey + ".percent' must be >= 0; row skipped");
                continue;
            }
            rows.put(tier, percent);
        }
        return TierTable.of(rows);
    }
}
