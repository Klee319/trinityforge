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
    /** 1回の伐採で壊す葉の絶対上限の既定値(2026-07-31 N2)。tier表に leaves-max が無いときのフォールバック。 */
    private static final int DEFAULT_LEAVES_MAX = 512;
    /** 1tickあたりに壊す葉の枚数の既定値(2026-07-31 N2)。512枚なら約11tick=0.55秒で樹冠が消える。 */
    private static final int DEFAULT_LEAVES_PER_TICK = 48;
    /**
     * 「木全体」を把握する走査の上限本数の既定値(2026-07-31 G1 レビュー指摘6b)。
     * {@link com.trinityforge.woodcutting.TreeScan#TREE_SCAN_LIMIT} と同じ値。
     * <b>Java 側の既定値をここで変えると出荷 yml とドリフトする</b>ので、変えるなら両方を同時に直すこと。
     */
    private static final int DEFAULT_SCAN_LIMIT = 512;

    /**
     * {@code tree-fell.tiers.<tier>} の1行 (2026-07-31 N2 で {@code leaves-max} 列を追加)。
     *
     * @param maxExtraLogs 連鎖伐採する原木の上限本数(トリガー原木を含まない)。
     * @param leavesMax    その tier での葉の絶対上限。0 なら未指定(グローバルへフォールバック)。
     */
    public record TreeFellTier(int maxExtraLogs, int leavesMax) {
    }

    private volatile int treeFellMaxExtraLogs = DEFAULT_MAX_EXTRA_LOGS;
    private volatile int treeFellCooldownTicks = DEFAULT_COOLDOWN_TICKS;
    private volatile TierTable<TreeFellTier> treeFellTiers = TierTable.empty();
    private volatile boolean treeFellBreakLeaves = true;
    private volatile int treeFellLeavesPerLog = DEFAULT_LEAVES_PER_LOG;
    private volatile int treeFellLeavesMax = DEFAULT_LEAVES_MAX;
    private volatile int treeFellLeavesPerTick = DEFAULT_LEAVES_PER_TICK;
    private volatile boolean treeFellLeavesDecayOnly = true;
    private volatile int treeFellScanLimit = DEFAULT_SCAN_LIMIT;
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
        return treeFellTiers.resolve(tier).map(TreeFellTier::maxExtraLogs).orElse(treeFellMaxExtraLogs);
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
     * <b>旧キー</b>({@code tree-fell.leaves-per-log}, 2026-07-30)。巻き込む葉の上限枚数を「実際に伐った
     * 原木の本数 × この値」で決めていた係数。2026-07-31 N2 で絶対枚数の {@code leaves-max} が主役に
     * なったため、{@code leaves-max} を 0 以下にしたときの旧挙動フォールバックとしてのみ効く
     * ({@link #treeFellMaxLeaves(int, int)} 参照)。
     */
    public int treeFellLeavesPerLog() {
        return treeFellLeavesPerLog;
    }

    /**
     * {@code tree-fell.leaves-max}(グローバル)。tier表に {@code leaves-max} が無いときのフォールバック。
     * 0 以下なら「旧挙動({@code leaves-per-log} × 伐った本数)を使う」の意味になる。
     */
    public int treeFellLeavesMax() {
        return treeFellLeavesMax;
    }

    /**
     * 1回の伐採で壊す葉の上限枚数を解決する (2026-07-31 N2)。
     *
     * <p>解決順: {@code tree-fell.tiers.<tier>.leaves-max} → グローバル {@code tree-fell.leaves-max}
     * → 旧挙動 {@code brokenLogs × leaves-per-log}。tier1=128 / tier2=256 / tier3=512 / tier4=1024 が
     * 出荷値で、要望「バニラより大幅に速く」に対して樹冠が1回で消え切る水準に置いている。
     *
     * @param tier       プレイヤーの解放済み最高tier
     * @param brokenLogs 実際に連鎖伐採した原木の本数(旧挙動フォールバックでのみ使う)
     */
    public int treeFellMaxLeaves(int tier, int brokenLogs) {
        int tierMax = treeFellTiers.resolve(tier).map(TreeFellTier::leavesMax).orElse(0);
        if (tierMax > 0) {
            return tierMax;
        }
        if (treeFellLeavesMax > 0) {
            return treeFellLeavesMax;
        }
        return Math.max(0, brokenLogs) * treeFellLeavesPerLog;
    }

    /**
     * 1tickあたりに壊す葉の枚数({@code tree-fell.leaves-per-tick})。0 以下なら同tickで全部壊す
     * (チャンク更新とライティング更新が集中するので非推奨)。
     */
    public int treeFellLeavesPerTick() {
        return treeFellLeavesPerTick;
    }

    /**
     * {@code tree-fell.leaves-decay-only}(既定 true)。true なら「バニラなら崩壊する葉」だけを壊す —
     * 設置された葉({@code persistent})は壊さず、残った原木から距離6以内で支えられた葉も残す。
     *
     * <p><b>既定 true を崩さないこと</b>: 葉の上限を桁で上げたので、これを false にすると
     * 樹冠が癒着したジャングル/ダークオークで「1本伐ると林冠が連鎖消滅する」。
     */
    public boolean treeFellLeavesDecayOnly() {
        return treeFellLeavesDecayOnly;
    }

    /**
     * {@code tree-fell.scan-limit}(既定 512): 「木全体」を把握するときに読むブロックの上限本数
     * (2026-07-31 G1 レビュー指摘6b で定数から config へ出した)。
     *
     * <p><b>伐る本数の上限({@code max-extra-logs})とは別枠</b> — 上限で伐り残した幹も葉の走査の種に
     * 必要なため。1回の原木破壊でメインスレッドがこの本数ぶんの {@code getBlockAt} を回すので、
     * 巨木林で tick に効くようなら下げるためのレバー。0以下なら既定値へ戻す(走査ゼロにはしない —
     * 一括伐採そのものが無言で死ぬため)。
     */
    public int treeFellScanLimit() {
        return treeFellScanLimit;
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
        // leaves-max / leaves-per-tick は「0以下 = 別の意味を持つ」ので clampPositiveInt は通さない
        // (leaves-max<=0 は旧挙動フォールバック、leaves-per-tick<=0 は同tickで全部)。
        this.treeFellLeavesMax = yaml.getInt("tree-fell.leaves-max", DEFAULT_LEAVES_MAX);
        this.treeFellLeavesPerTick = yaml.getInt("tree-fell.leaves-per-tick", DEFAULT_LEAVES_PER_TICK);
        this.treeFellLeavesDecayOnly = yaml.getBoolean("tree-fell.leaves-decay-only", true);
        this.treeFellScanLimit = clampPositiveInt(
                yaml.getInt("tree-fell.scan-limit", DEFAULT_SCAN_LIMIT),
                "tree-fell.scan-limit", DEFAULT_SCAN_LIMIT, log);
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
     * {@code tree-fell.tiers: {<tier>: {max-extra-logs: N, leaves-max: M}}} (2026-07-25 §1/§6 Q1、
     * {@code leaves-max} は 2026-07-31 N2 追加). Absent/empty section yields {@link TierTable#empty()}.
     * A tier key that is not a positive integer, or a row missing/with a non-positive
     * {@code max-extra-logs}, is skipped with a warning — <b>行スキップ条件は据え置き</b>(変えると既存 yml の
     * 意味が変わる)。{@code leaves-max} の省略/非正値は 0(グローバルへフォールバック)として扱い、警告も出さない。
     */
    private static TierTable<TreeFellTier> parseTreeFellTiers(ConfigurationSection section, Logger log) {
        if (section == null) {
            return TierTable.empty();
        }
        Map<Integer, TreeFellTier> rows = new LinkedHashMap<>();
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
            int leavesMax = Math.max(0, row.getInt("leaves-max", 0));
            rows.put(tier, new TreeFellTier(maxExtraLogs, leavesMax));
        }
        return TierTable.of(rows);
    }
}
