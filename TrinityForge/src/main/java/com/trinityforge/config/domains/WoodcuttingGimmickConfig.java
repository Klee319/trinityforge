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
 * Loader for {@code stats/woodcutting-gimmick.yml}: tuning for the伐採スキルツリーflag系
 * dedicated-effect consumers that have no existing config home plus the {@code woodcutting} drop-table
 * categories (2026-07-23 stat-gate-overhaul §4 — replaces the old
 * {@code apple-drop}/{@code golden-apple-drop}/{@code crystal-apple-drop} hardcoded consumers). Same
 * raw-YAML loader style as {@link MiningGimmickConfig}.
 *
 * <p>2026-07-25 gather-rework-active-framework §6 Q1: {@code small-tree-fell}/{@code large-tree-fell} were
 * consolidated into one tiered {@code feature:tree-fell} (see {@code FeatureEffectRegistry}). The old
 * two-scalar {@code tree-fell.small-max-extra-logs}/{@code tree-fell.large-max-extra-logs} shape is
 * replaced by a single {@link #treeFellMaxExtraLogs()} scalar (tier-1 fallback default, matching the old
 * "small" default) plus an optional {@code tree-fell.tiers} table — this is the one gimmick-config shape
 * this wave actually changes rather than merely adding an optional {@code tiers:} block to (there is no
 * way to preserve both the old small AND large defaults as a single legacy scalar, so the migration
 * populates {@code tiers: {1: 8, 3: 64}} explicitly, matching the pre-existing small/large defaults 1:1 —
 * see {@code TreeFellingListener}).
 */
public final class WoodcuttingGimmickConfig {

    public static final String PATH = "stats/woodcutting-gimmick.yml";

    private static final int DEFAULT_MAX_EXTRA_LOGS = 8;
    private static final int DEFAULT_COOLDOWN_TICKS = 200;
    /** 原木1本あたり巻き込む葉の枚数の既定倍率(2026-07-30 一括伐採の葉巻き込み)。 */
    private static final int DEFAULT_LEAVES_PER_LOG = 6;

    private volatile int treeFellMaxExtraLogs = DEFAULT_MAX_EXTRA_LOGS;
    private volatile int treeFellCooldownTicks = DEFAULT_COOLDOWN_TICKS;
    private volatile TierTable<Integer> treeFellTiers = TierTable.empty();
    private volatile boolean treeFellBreakLeaves = true;
    private volatile int treeFellLeavesPerLog = DEFAULT_LEAVES_PER_LOG;
    private volatile Map<String, DropTableConfig.Category> dropTables = Map.of();

    /** {@code tree-fell} の一括伐採上限本数(トリガー原木を含まない)。tiers未定義時のグローバル既定値。 */
    public int treeFellMaxExtraLogs() {
        return treeFellMaxExtraLogs;
    }

    /**
     * {@code tree-fell} の一括伐採上限本数を {@code tier}(プレイヤーの解放済み最高tier)で解決する。
     * {@code tree-fell.tiers} が未定義、または {@code tier} 未満の行しかない場合は
     * {@link #treeFellMaxExtraLogs()}(グローバルscalar)へフォールバックする。
     */
    public int treeFellMaxExtraLogs(int tier) {
        return treeFellTiers.resolve(tier).orElse(treeFellMaxExtraLogs);
    }

    /** 一括伐採のプレイヤー毎クールダウン(tick)。 */
    public int treeFellCooldownTicks() {
        return treeFellCooldownTicks;
    }

    /**
     * 一括伐採で、伐り倒した幹に繋がる葉も一緒に壊すか({@code tree-fell.break-leaves}, 2026-07-30)。
     * 幹だけ消えて葉が空中に浮いたまま残るのを避けるための設定。
     */
    public boolean treeFellBreakLeaves() {
        return treeFellBreakLeaves;
    }

    /**
     * 巻き込む葉の上限枚数を「実際に伐った原木の本数 × この値」で決める
     * ({@code tree-fell.leaves-per-log})。tier表を葉のぶんだけ二重に持たずに、原木の上限tierへ
     * 自動追従させるための係数。
     */
    public int treeFellLeavesPerLog() {
        return treeFellLeavesPerLog;
    }

    /** {@code drop-tables.categories} (2026-07-23 §4): カテゴリid -&gt; 定義。ゲート/抽選は {@code DropTablePolicy} が担う。 */
    public Map<String, DropTableConfig.Category> dropTables() {
        return dropTables;
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

        this.treeFellMaxExtraLogs = clampPositiveInt(
                yaml.getInt("tree-fell.max-extra-logs", DEFAULT_MAX_EXTRA_LOGS),
                "tree-fell.max-extra-logs", DEFAULT_MAX_EXTRA_LOGS, log);
        this.treeFellCooldownTicks = clampPositiveInt(
                yaml.getInt("tree-fell.cooldown-ticks", DEFAULT_COOLDOWN_TICKS),
                "tree-fell.cooldown-ticks", DEFAULT_COOLDOWN_TICKS, log);
        this.treeFellTiers = parseTreeFellTiers(yaml.getConfigurationSection("tree-fell.tiers"), log);
        this.treeFellBreakLeaves = yaml.getBoolean("tree-fell.break-leaves", true);
        this.treeFellLeavesPerLog = clampPositiveInt(
                yaml.getInt("tree-fell.leaves-per-log", DEFAULT_LEAVES_PER_LOG),
                "tree-fell.leaves-per-log", DEFAULT_LEAVES_PER_LOG, log);
        this.dropTables = DropTableConfig.parseCategories(
                yaml.getConfigurationSection("drop-tables.categories"), true, PATH, log);

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
     * {@code tree-fell.tiers: {<tier>: {max-extra-logs: N}}} (2026-07-25 §1/§6 Q1). Absent/empty section
     * yields {@link TierTable#empty()}. A tier key that is not a positive integer, or a row missing/with a
     * non-positive {@code max-extra-logs}, is skipped with a warning.
     */
    private static TierTable<Integer> parseTreeFellTiers(ConfigurationSection section, Logger log) {
        if (section == null) {
            return TierTable.empty();
        }
        Map<Integer, Integer> rows = new LinkedHashMap<>();
        for (String tierKey : section.getKeys(false)) {
            int tier;
            try {
                tier = Integer.parseInt(tierKey.trim());
                if (tier <= 0) {
                    log.warning("[" + PATH + "] 'tree-fell.tiers." + tierKey + "' key must be a positive integer; skipped");
                    continue;
                }
            } catch (NumberFormatException ex) {
                log.warning("[" + PATH + "] 'tree-fell.tiers." + tierKey + "' key is not an integer; skipped");
                continue;
            }
            ConfigurationSection row = section.getConfigurationSection(tierKey);
            int maxExtraLogs = row == null ? 0 : row.getInt("max-extra-logs", 0);
            if (maxExtraLogs <= 0) {
                log.warning("[" + PATH + "] 'tree-fell.tiers." + tierKey + ".max-extra-logs' must be > 0; row skipped");
                continue;
            }
            rows.put(tier, maxExtraLogs);
        }
        return TierTable.of(rows);
    }
}
