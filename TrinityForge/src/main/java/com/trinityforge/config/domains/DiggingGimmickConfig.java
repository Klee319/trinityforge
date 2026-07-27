package com.trinityforge.config.domains;

import com.trinityforge.skilltree.effects.TierTable;
import com.trinityforge.stats.DropTableConfig;
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
 * Loader for {@code stats/digging-gimmick.yml}: the {@code digging} drop-table categories
 * (2026-07-23 stat-gate-overhaul §4 — new listener, {@code DiggingGimmickListener}) triggered by
 * shovel-appropriate block breaks (target判定 = {@code digging_progression.yml}'s {@code digging_break}
 * table, same classification {@code NativeSkillExperienceListener} already uses). Same raw-YAML loader
 * style as {@link MiningGimmickConfig}.
 */
public final class DiggingGimmickConfig {

    public static final String PATH = "stats/digging-gimmick.yml";

    private static final double DEFAULT_DURABILITY_PER_PERCENT = 100.0;

    private volatile Map<String, DropTableConfig.Category> dropTables = Map.of();
    private volatile double durabilityPerPercent = DEFAULT_DURABILITY_PER_PERCENT;
    /**
     * {@code durability-exp.tiers.<tier>.durability-per-percent} (2026-07-26 tier-expand): 未定義なら
     * {@link #durabilityPerPercent()}(グローバルscalar)へ完全後方互換フォールバックする。{@code tier} は
     * {@code digging-durability-vanilla-exp}/{@code digging-durability-job-exp} それぞれの
     * {@code DedicatedEffectsConfig#valueMax}(= C-1/C-2ノードの上限%そのもの)を流用する — この2機構は
     * 依然 {@code FeatureEffectParam.LEVEL}(値=直接%)のままで、tierテーブルはあくまで「その%ノードを
     * 持つ人の変換レートを個別チューニングしたい場合の追加オプション」という位置づけ(スキルツリー
     * エディタのtierセレクトメニュー化は対象外 — LEVELノードの value は従来どおり自由数値入力のまま)。
     */
    private volatile TierTable<Double> durabilityPerPercentTiers = TierTable.empty();

    /** {@code drop-tables.categories} (2026-07-23 §4): カテゴリid -&gt; 定義。ゲート/抽選は {@code DropTablePolicy} が担う。 */
    public Map<String, DropTableConfig.Category> dropTables() {
        return dropTables;
    }

    /**
     * {@code durability-exp.durability-per-percent} (2026-07-25、切削C-1/C-2): 累積シャベル耐久消費量
     * 1%ボーナスに必要な量。{@link com.trinityforge.listeners.DiggingDurabilityExpListener} が
     * これで累積量をパーセントへ変換する(上限は各ノードの dedicated-effects value)。
     */
    public double durabilityPerPercent() {
        return durabilityPerPercent;
    }

    /**
     * {@code durability-per-percent} を {@code tier}(呼び出し側が渡す、通常はC-1/C-2ノードの
     * {@code valueMax})で解決する。{@code durability-exp.tiers} が未定義、または {@code tier} 未満の
     * 行しか無い場合は {@link #durabilityPerPercent()}(グローバルscalar)へ完全後方互換フォールバックする。
     */
    public double durabilityPerPercent(int tier) {
        return durabilityPerPercentTiers.resolve(tier).orElse(durabilityPerPercent);
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

        this.dropTables = DropTableConfig.parseCategories(
                yaml.getConfigurationSection("drop-tables.categories"), true, PATH, log);
        this.durabilityPerPercent = clampPositive(
                yaml.getDouble("durability-exp.durability-per-percent", DEFAULT_DURABILITY_PER_PERCENT), log);
        this.durabilityPerPercentTiers = parseDurabilityPerPercentTiers(
                yaml.getConfigurationSection("durability-exp.tiers"), log);

        log.info("[" + PATH + "] loaded " + this.dropTables.size() + " drop-table categor(y/ies) OK");
        return true;
    }

    private static double clampPositive(double raw, Logger log) {
        if (!Double.isFinite(raw) || raw <= 0.0) {
            log.warning("[" + PATH + "] 'durability-exp.durability-per-percent' must be > 0 (was " + raw
                    + "); using default " + DEFAULT_DURABILITY_PER_PERCENT);
            return DEFAULT_DURABILITY_PER_PERCENT;
        }
        return raw;
    }

    /**
     * {@code durability-exp.tiers: {<tier>: {durability-per-percent: N}}} (2026-07-26 tier-expand)。
     * Absent/empty section yields {@link TierTable#empty()} (省略時は完全後方互換)。A tier key that is not
     * a positive integer, or a row missing/with a non-positive {@code durability-per-percent}, is skipped
     * with a warning.
     */
    private static TierTable<Double> parseDurabilityPerPercentTiers(ConfigurationSection section, Logger log) {
        if (section == null) {
            return TierTable.empty();
        }
        Map<Integer, Double> rows = new LinkedHashMap<>();
        for (String tierKey : section.getKeys(false)) {
            int tier;
            try {
                tier = Integer.parseInt(tierKey.trim());
                if (tier <= 0) {
                    log.warning("[" + PATH + "] 'durability-exp.tiers." + tierKey + "' key must be a positive integer; skipped");
                    continue;
                }
            } catch (NumberFormatException ex) {
                log.warning("[" + PATH + "] 'durability-exp.tiers." + tierKey + "' key is not an integer; skipped");
                continue;
            }
            ConfigurationSection row = section.getConfigurationSection(tierKey);
            double rate = row == null ? 0.0 : row.getDouble("durability-per-percent", 0.0);
            if (!Double.isFinite(rate) || rate <= 0.0) {
                log.warning("[" + PATH + "] 'durability-exp.tiers." + tierKey
                        + ".durability-per-percent' must be > 0; row skipped");
                continue;
            }
            rows.put(tier, rate);
        }
        return TierTable.of(rows);
    }
}
