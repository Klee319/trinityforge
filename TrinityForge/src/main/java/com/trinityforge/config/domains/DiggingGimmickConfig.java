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

    // --- 2026-08-18 (W-59): haste-active-digging (SCALE) — mining-gimmick.yml の haste-active-mining
    // と同じ形の独立したscalar+tiers表。あちらとは別クラス(DiggingGimmickConfig)の別フィールドなので、
    // 数値・段数はミラーしない(意図的に独立して調整できる。ミラーしていない=バグではない)。
    private static final int DEFAULT_HASTE_AMPLIFIER = 1;
    private static final int DEFAULT_HASTE_DURATION_TICKS = 120;
    private static final int DEFAULT_HASTE_COOLDOWN_TICKS = 800;

    /** 1 tier行(cap-percent必須、durability-per-percentは省略可でグローバル既定値へフォールバック)。 */
    public record DurabilityExpTierRow(double capPercent, Double durabilityPerPercentOverride) {}

    /** {@code haste-active-digging.tiers.<tier>.{amplifier,duration-ticks}} 1行(CTはtier不変、mining側と同じ設計)。 */
    public record HasteTierValues(int amplifier, int durationTicks) {}

    private volatile Map<String, DropTableConfig.Category> dropTables = Map.of();
    private volatile double durabilityPerPercent = DEFAULT_DURABILITY_PER_PERCENT;
    private volatile TierTable<DurabilityExpTierRow> vanillaExpTiers = TierTable.empty();
    private volatile TierTable<DurabilityExpTierRow> jobExpTiers = TierTable.empty();
    private volatile int hasteAmplifier = DEFAULT_HASTE_AMPLIFIER;
    private volatile int hasteDurationTicks = DEFAULT_HASTE_DURATION_TICKS;
    private volatile int hasteCooldownTicks = DEFAULT_HASTE_COOLDOWN_TICKS;
    private volatile TierTable<HasteTierValues> hasteTiers = TierTable.empty();
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

    /** haste-active-digging が付与する HASTE の amplifier。tiers未定義時のグローバル既定値。 */
    public int hasteAmplifier() {
        return hasteAmplifier;
    }

    /** haste-active-digging の持続時間(tick)。tiers未定義時のグローバル既定値。 */
    public int hasteDurationTicks() {
        return hasteDurationTicks;
    }

    /**
     * haste-active-digging のクールダウン(tick)。mining側と同じ設計方針で、tierは一切CTに影響させない
     * (段階が支配するのは {@link #hasteAmplifier(int)}/{@link #hasteDurationTicks(int)} の効果量だけ)。
     */
    public int hasteCooldownTicks() {
        return hasteCooldownTicks;
    }

    /** tier解決込みの amplifier(floor+フォールバック則、{@link #vanillaExpCapPercent(int)}と同じ規則)。 */
    public int hasteAmplifier(int tier) {
        return hasteTiers.resolve(tier).map(HasteTierValues::amplifier).orElse(hasteAmplifier);
    }

    /** tier解決込みの持続時間(tick)。 */
    public int hasteDurationTicks(int tier) {
        return hasteTiers.resolve(tier).map(HasteTierValues::durationTicks).orElse(hasteDurationTicks);
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
        this.hasteAmplifier = Math.max(0,
                yaml.getInt("haste-active-digging.amplifier", DEFAULT_HASTE_AMPLIFIER));
        this.hasteDurationTicks = clampPositiveInt(
                yaml.getInt("haste-active-digging.duration-ticks", DEFAULT_HASTE_DURATION_TICKS),
                "haste-active-digging.duration-ticks", DEFAULT_HASTE_DURATION_TICKS, log);
        this.hasteCooldownTicks = clampPositiveInt(
                yaml.getInt("haste-active-digging.cooldown-ticks", DEFAULT_HASTE_COOLDOWN_TICKS),
                "haste-active-digging.cooldown-ticks", DEFAULT_HASTE_COOLDOWN_TICKS, log);
        this.hasteTiers = parseHasteTiers(
                yaml.getConfigurationSection("haste-active-digging.tiers"), log);

        log.info("[" + PATH + "] loaded " + this.dropTables.size() + " drop-table categor(y/ies) OK");
        return true;
    }

    /** Non-finite/non-positive guard for a tick/count value: falls back to {@code fallback}, never throws. */
    private static int clampPositiveInt(int raw, String key, int fallback, Logger log) {
        if (raw <= 0) {
            log.warning("[" + PATH + "] '" + key + "' must be > 0 (was " + raw + "); using default " + fallback);
            return fallback;
        }
        return raw;
    }

    /**
     * {@code haste-active-digging.tiers: {<tier>: {amplifier, duration-ticks}}} — mining-gimmick.yml の
     * 同名セクションと同じ形/同じ規則(CTはtierに一切依存しない)。Absent/empty section yields
     * {@link TierTable#empty()}. A row with a non-positive {@code duration-ticks} is skipped with a
     * warning ({@code amplifier} may legitimately be 0).
     */
    private static TierTable<HasteTierValues> parseHasteTiers(ConfigurationSection section, Logger log) {
        if (section == null) {
            return TierTable.empty();
        }
        Map<Integer, HasteTierValues> rows = new LinkedHashMap<>();
        for (String tierKey : section.getKeys(false)) {
            int tier;
            try {
                tier = Integer.parseInt(tierKey.trim());
                if (tier <= 0) {
                    log.warning("[" + PATH + "] 'haste-active-digging.tiers." + tierKey
                            + "' key must be a positive integer; skipped");
                    continue;
                }
            } catch (NumberFormatException ex) {
                log.warning("[" + PATH + "] 'haste-active-digging.tiers." + tierKey + "' key is not an integer; skipped");
                continue;
            }
            ConfigurationSection row = section.getConfigurationSection(tierKey);
            if (row == null) {
                log.warning("[" + PATH + "] 'haste-active-digging.tiers." + tierKey + "' is not a map; row skipped");
                continue;
            }
            int amplifier = Math.max(0, row.getInt("amplifier", DEFAULT_HASTE_AMPLIFIER));
            int durationTicks = row.getInt("duration-ticks", 0);
            if (durationTicks <= 0) {
                log.warning("[" + PATH + "] 'haste-active-digging.tiers." + tierKey
                        + "' duration-ticks must be > 0; row skipped");
                continue;
            }
            rows.put(tier, new HasteTierValues(amplifier, durationTicks));
        }
        return TierTable.of(rows);
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
