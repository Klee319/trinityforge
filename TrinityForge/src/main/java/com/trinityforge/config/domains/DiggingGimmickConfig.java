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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/digging-gimmick.yml}: the {@code digging} drop-table categories
 * (2026-07-23 stat-gate-overhaul §4 — new listener, {@code DiggingGimmickListener}) triggered by
 * shovel-appropriate block breaks (target判定 = {@code digging_progression.yml}'s {@code digging_break}
 * table, same classification {@code NativeSkillExperienceListener} already uses). Same raw-YAML loader
 * style as {@link MiningGimmickConfig}.
 *
 * <p><b>耐久累計EXPの tierテーブル (2026-07-28 数値のギミックyml集約)</b>: {@code durability-exp} は
 * feature別(vanilla-exp / job-exp)に独立した tiers表を持つ。旧versionは「上限%(50/25)をそのままtier
 * 番号として流用する」アンチパターンで、C-1/C-2が同じ数値空間を共有していたため tier を足すと%の意味が
 * 壊れた(digging-gimmick.yml ヘッダ参照)。{@code tier} は {@code digging-durability-vanilla-exp}/
 * {@code digging-durability-job-exp}(2026-07-28 から {@code FeatureEffectParam.SCALE}) それぞれの
 * {@code DedicatedEffectsConfig#valueMax} で解決する。解決規則(smithing-gimmickと同じ): tierの完全一致
 * → 無ければ tier以下で最大の行 → それも無ければ cap-percent 0(無効)。完全一致しなかった場合は移行事故
 * 検知のため tierごとに一度だけ WARNING を出す(古い digging.yml がvalue 25/50直書きのまま配備先に残って
 * いると、value自体が tier として解釈され意図しない%へ化ける事故を防ぐため)。
 */
public final class DiggingGimmickConfig {

    public static final String PATH = "stats/digging-gimmick.yml";

    private static final double DEFAULT_DURABILITY_PER_PERCENT = 100.0;

    /** 1 tier行(cap-percent必須、durability-per-percentは省略可でグローバル既定値へフォールバック)。 */
    public record DurabilityExpTierRow(double capPercent, Double durabilityPerPercentOverride) {}

    private volatile Map<String, DropTableConfig.Category> dropTables = Map.of();
    private volatile double durabilityPerPercent = DEFAULT_DURABILITY_PER_PERCENT;
    private volatile TierTable<DurabilityExpTierRow> vanillaExpTiers = TierTable.empty();
    private volatile TierTable<DurabilityExpTierRow> jobExpTiers = TierTable.empty();
    private volatile Logger log;
    /** 「tierの完全一致が取れなかった」WARNINGを同じtierに対して1度だけ出すための抑制セット。reload毎に作り直す。 */
    private volatile Set<String> nonExactTierWarned = ConcurrentHashMap.newKeySet();

    /** {@code drop-tables.categories} (2026-07-23 §4): カテゴリid -&gt; 定義。ゲート/抽選は {@code DropTablePolicy} が担う。 */
    public Map<String, DropTableConfig.Category> dropTables() {
        return dropTables;
    }

    /**
     * {@code durability-exp.durability-per-percent} (2026-07-25、切削C-1/C-2): 累積シャベル耐久消費量
     * 1%ボーナスに必要な量(グローバル既定値)。{@link com.trinityforge.listeners.DiggingDurabilityExpListener}
     * が{@link #durabilityPerPercentForVanillaExp(int)}/{@link #durabilityPerPercentForJobExp(int)}
     * 経由でこれを使う。
     */
    public double durabilityPerPercent() {
        return durabilityPerPercent;
    }

    /** バニラEXPボーナス(C-1)の上限%を {@code tier} で解決する。tier未到達なら0(無効)。 */
    public double vanillaExpCapPercent(int tier) {
        return resolveRow(vanillaExpTiers, tier, "durability-exp.vanilla-exp")
                .map(DurabilityExpTierRow::capPercent).orElse(0.0);
    }

    /** 職業EXPボーナス(C-2)の上限%を {@code tier} で解決する。tier未到達なら0(無効)。 */
    public double jobExpCapPercent(int tier) {
        return resolveRow(jobExpTiers, tier, "durability-exp.job-exp")
                .map(DurabilityExpTierRow::capPercent).orElse(0.0);
    }

    /** バニラEXPボーナス(C-1)の変換レート。行が {@code durability-per-percent} を持たなければグローバル既定値。 */
    public double durabilityPerPercentForVanillaExp(int tier) {
        return resolveRow(vanillaExpTiers, tier, "durability-exp.vanilla-exp")
                .map(DurabilityExpTierRow::durabilityPerPercentOverride).orElse(durabilityPerPercent);
    }

    /** 職業EXPボーナス(C-2)の変換レート。行が {@code durability-per-percent} を持たなければグローバル既定値。 */
    public double durabilityPerPercentForJobExp(int tier) {
        return resolveRow(jobExpTiers, tier, "durability-exp.job-exp")
                .map(DurabilityExpTierRow::durabilityPerPercentOverride).orElse(durabilityPerPercent);
    }

    private Optional<DurabilityExpTierRow> resolveRow(TierTable<DurabilityExpTierRow> table, int tier, String fieldLabel) {
        Optional<DurabilityExpTierRow> resolved = table.resolve(tier);
        if (resolved.isEmpty()) {
            warnOnce(fieldLabel, tier, "tier " + tier + " に一致/以下の行が無く、上限%は0(無効)として扱います");
            return resolved;
        }
        Optional<Integer> resolvedKey = table.resolvedKey(tier);
        if (resolvedKey.isPresent() && resolvedKey.get() != tier) {
            warnOnce(fieldLabel, tier, "tier " + tier + " の完全一致行が無く、tier " + resolvedKey.get()
                    + " の行へフォールバックしました(旧versionのdigging.ymlが配備先に残っていないか確認してください)");
        }
        return resolved;
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

        this.dropTables = DropTableConfig.parseCategories(
                yaml.getConfigurationSection("drop-tables.categories"), true, PATH, log);
        this.durabilityPerPercent = clampPositive(
                yaml.getDouble("durability-exp.durability-per-percent", DEFAULT_DURABILITY_PER_PERCENT), log);
        this.vanillaExpTiers = parseDurabilityExpTiers(
                yaml.getConfigurationSection("durability-exp.vanilla-exp.tiers"), "durability-exp.vanilla-exp.tiers", log);
        this.jobExpTiers = parseDurabilityExpTiers(
                yaml.getConfigurationSection("durability-exp.job-exp.tiers"), "durability-exp.job-exp.tiers", log);

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
     * {@code <section>: {<tier>: {cap-percent: N, durability-per-percent: N(任意)}}} を解析する。
     * Absent/empty section yields {@link TierTable#empty()}。{@code cap-percent} が欠落/負値の行は
     * 警告を出して skip する。{@code durability-per-percent} は省略可(省略時はグローバル既定値へ委譲)。
     */
    private static TierTable<DurabilityExpTierRow> parseDurabilityExpTiers(
            ConfigurationSection section, String fieldLabel, Logger log) {
        if (section == null) {
            return TierTable.empty();
        }
        Map<Integer, DurabilityExpTierRow> rows = new LinkedHashMap<>();
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
            double capPercent = row == null ? -1.0 : row.getDouble("cap-percent", -1.0);
            if (!Double.isFinite(capPercent) || capPercent < 0.0) {
                log.warning("[" + PATH + "] '" + fieldLabel + "." + tierKey + ".cap-percent' must be >= 0; row skipped");
                continue;
            }
            Double durabilityOverride = null;
            if (row != null && row.isSet("durability-per-percent")) {
                double raw = row.getDouble("durability-per-percent", -1.0);
                if (Double.isFinite(raw) && raw > 0.0) {
                    durabilityOverride = raw;
                } else {
                    log.warning("[" + PATH + "] '" + fieldLabel + "." + tierKey
                            + ".durability-per-percent' must be > 0; override ignored (using global default)");
                }
            }
            rows.put(tier, new DurabilityExpTierRow(capPercent, durabilityOverride));
        }
        return TierTable.of(rows);
    }
}
